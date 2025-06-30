package moe.karla


import moe.karla.miscellaneous.FlywayMigration
import moe.karla.miscellaneous.Storage
import moe.karla.config.{AppConfig, ServerConfig, ClientConfig}
import moe.karla.service.PrepareService
import moe.karla.endpoint.* 
import moe.karla.repo.MangaMetaRepo
import moe.karla.repo.MangaPageRepo
import moe.karla.service.TaskHelper
import moe.karla.service.DownloadHub


import zio.*
import zio.http.*
import zio.http.Header.AccessControlAllowOrigin
import zio.http.Middleware.CorsConfig
import zio.http.netty.NettyConfig
import zio.http.netty.NettyConfig.*

import zio.logging.consoleLogger
import zio.config.typesafe.TypesafeConfigProvider

import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill




object AppMain extends ZIOAppDefault:

  override val bootstrap: ZLayer[Any, Config.Error, Unit] =
    Runtime.removeDefaultLoggers >>> 
    Runtime.setConfigProvider(TypesafeConfigProvider.fromHoconFilePath("logger.conf")) >>> 
    consoleLogger()


  def appLogic = {
    for
      config <- ZIO.service[AppConfig]
      _ <- PrepareService.runOnce
      fiber <- DownloadHub.runDaemon
      port <- 
        Server.install(
          (TaskEndpoint.routes ++ BasicEndpoint.routes) @@
          Middleware.cors(
            CorsConfig(
              allowedOrigin = { _ => Some(AccessControlAllowOrigin.All) },
            )
          ) @@
          Middleware.serveResources(Path.empty / "static")
        )
      _ <- ZIO.log(s"Server started @ ${config.host}:${config.port}")
      _ <- ZIO.never
    yield ()
  }
  .provide(
    AppConfig.layer,
    PrepareService.layer,
    MangaMetaRepo.layer,
    MangaPageRepo.layer,
    DownloadHub.layer,
    TaskHelper.layer,
    Quill.Sqlite.fromNamingStrategy(SnakeCase),
    ServerConfig.layer, Server.customized,
    ClientConfig.layer, Client.customized, 
    Storage.dataSourceLayer,
    DnsResolver.default,
    Scope.default
  )

  def run = FlywayMigration.runMigrate *> appLogic.map(_ => ExitCode.success)