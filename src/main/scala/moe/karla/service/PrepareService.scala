package moe.karla.service



import moe.karla.config.AppConfig
import moe.karla.handler.*
import moe.karla.handler.default.* 
import moe.karla.metadata.*
import moe.karla.repo.*


import zio.*
import zio.stream.*

import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill

import scala.concurrent.duration.DurationInt

import java.nio.file.Paths
import java.nio.file.Files

import scala.concurrent.duration.DurationInt


class PrepareService(
  config: AppConfig,
  metaRepo: MangaMetaRepo,
  pageRepo: MangaPageRepo,
  quill: Quill.Sqlite[SnakeCase],
):


  def run = {
    ZIO.attemptBlockingIO(Files.createDirectories(Paths.get(config.downPath))) *>
    ZIO.attemptBlockingIO(Files.createDirectories(Paths.get(config.cachePath))) *>
    quill.transaction(
      metaRepo.setStateByStateIn(MangaMeta.State.Pending)(
        MangaMeta.State.Running, MangaMeta.State.WaitingForHandler
      ) *>
      pageRepo.setStateByStateIn(MangaPage.State.Pending)(MangaPage.State.Running)
    ) 
    *> ZIO.sleep(2 second) 
  }
  .unit
  .orDie


object PrepareService:

  def runOnce = ZIO.serviceWithZIO[PrepareService](_.run)

  val layer = 
    ZLayer {
      for
        config <- ZIO.service[AppConfig]
        quill <- ZIO.service[Quill.Sqlite[SnakeCase]]
        metaRepo <- ZIO.service[MangaMetaRepo]
        pageRepo <- ZIO.service[MangaPageRepo]
      yield PrepareService(config, metaRepo, pageRepo, quill)
    }