package moe.karla.handler.default


import moe.karla.config.AppConfig
import moe.karla.service.TaskHelper
import moe.karla.metadata.*


import zio.*
import zio.stream.*

import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.deriveConfig

import zio.http.*
import zio.http.Client
import zio.http.Request
import zio.http.Header.AcceptEncoding

import zio.schema.codec.DecodeError

import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill

import scala.concurrent.duration.DurationInt

import java.nio.file.*
import java.sql.SQLException
import java.util.UUID
import java.net.MalformedURLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter




object KemonoHandler:
  
  object KemonoConfig:
    
    final case class DownloadConfig(

      ignoreArchive: Boolean,

      ignoreLeftIfArchiveExists: Boolean
    )

    def load =
      ConfigProvider.fromHoconFile:
          new java.io.File("config/kemono.conf")
        .load:
          deriveConfig[KemonoConfig].mapKey(toKebabCase)
        .tapError:
          e => ZIO.logError(e.getMessage())


    def layer = ZLayer.fromZIO(load)
    

  end KemonoConfig
  

  import KemonoConfig.DownloadConfig


  final case class KemonoConfig(

    agent: Option[String],

    patterns: List[String],

    titlePattern: String,

    download: DownloadConfig
  )


  given Config[KemonoConfig] = deriveConfig[KemonoConfig]


  final case class ParsingError(message: String) extends RuntimeException(message)

  final case class ServerError(message: String) extends RuntimeException(message)

  final case class BypassError(message: String) extends RuntimeException(message)

  final case class LostMetaError(message: String) extends RuntimeException(message)


  trait IdleIndicator:

    def isIdle: UIO[Boolean]

    def setIdle(value: Boolean): UIO[Unit]
  
  end IdleIndicator


  final class IdleIndicatorLive(ref: Ref[Boolean]) extends IdleIndicator: 

    def isIdle = ref.get

    def setIdle(value: Boolean) = ref.set(value)

  end IdleIndicatorLive

  /*
  trait RequestCookiesHolder:

    def getCookies: UIO[Chunk[Cookie.Request]]

    def setCookies(cookies: Chunk[Cookie.Request]): UIO[Unit]
  
  end RequestCookiesHolder

  
  final class RequestCookiesHolderLive(cookiesRef: Ref[Chunk[Cookie.Request]]) extends RequestCookiesHolder:

    def getCookies: UIO[Chunk[Cookie.Request]] = cookiesRef.get

    def setCookies(cookies: Chunk[Cookie.Request]): UIO[Unit] = 
      cookiesRef.set(cookies)
  */

  val layer: ZLayer[TaskHelper & AppConfig & CommonConfig, Nothing, KemonoHandler] =
    ZLayer {
      for
        kemonoConfig <- KemonoConfig.load.orDie
        appConfig <- ZIO.service[AppConfig]
        commonConfig <- ZIO.service[CommonConfig]
        idleIndicator <- Ref.make(true).map(IdleIndicatorLive(_))
        //cookiesHolder <- Ref.make(Chunk.empty[Cookie.Request]).map(RequestCookiesHolderLive(_))
        taskHelper <- ZIO.service[TaskHelper]
      yield 
        KemonoHandler(
          kemonoConfig, 
          appConfig.downPath, 
          appConfig.cachePath, 
          commonConfig.defaultUserAgent, 
          idleIndicator, 
          taskHelper
        )
    }
  

  final case class FileMeta(
    name: Option[String],
    path: String,
  )

  final case class DefiniteFileMeta(
    name: String, path: String,
  )

  final case class LesserPostMeta(
    id: String,
    user: String,
    title: String,
    service: String,
    published: LocalDateTime,
    file: FileMeta,
    attachments: List[FileMeta]
  )


  final case class ApiPostResp(
    post: LesserPostMeta
  )

  final case class ApiProfileResp(
    name: String,
    public_id: Option[String],
    service: String,
  )

end KemonoHandler



extension [A <: String] (str: A)
  private def filtered = 
    str.strip
      .replace('?', '？')
      .replace('（', '(')
      .replace('）', ')')
      .replace(":", "：")
      .replace("/", "／")
      .replace("\\", "＼")
      .replace("*", "＊")
      .replaceAll("[\\\\/:*?\"<>|]", " ")


final class KemonoHandler(
  
  config: KemonoHandler.KemonoConfig,

  downPath: String,
  
  cachePath: String,

  defaultUserAgent: String,

  idleIndicator: KemonoHandler.IdleIndicator,

  taskHelper: TaskHelper,

) extends moe.karla.handler.Handler:


  def canHandle(url: String) =
    ZIO.succeed(config.patterns.exists(url.matches(_)))


  def isIdle = idleIndicator.isIdle


  private def defaultRemoveFile(path: String) =
    ZIO.attemptBlockingIO(
      Files.deleteIfExists(Paths.get(path))
    )
    .retry(Schedule.recurs(3) && Schedule.spaced(2 seconds))

  
  private def defaultMoveFile(src: String, to: String) =
    ZIO.attemptBlockingIO(
      Files.move(
        Paths.get(src), 
        Paths.get(to), 
        StandardCopyOption.REPLACE_EXISTING
      )
    )
    .retry(Schedule.recurs(3) && Schedule.spaced(2 seconds))


  private def configureRequest(url: URL) =
    Request.get(url)
      .removeHeader("User-Agent")
      .addHeader("User-Agent", config.agent.getOrElse(defaultUserAgent))
      .addHeader(AcceptEncoding(AcceptEncoding.GZip(), AcceptEncoding.Deflate()))
  

  import KemonoHandler.*

  
  private val retryPolicy = 
    Schedule.recurs(4) && 
    Schedule.spaced(6 second) && 
    Schedule.fibonacci(800 millis)


  extension [R, E <: Throwable, A] (mo: ZIO[R, E, A])
    private def defaultRetry = 
      mo.retry(retryPolicy && Schedule.recurWhile[Throwable] {
        case _: (BypassError | ParsingError | DecodeError) => false
        case _ => true
      })



  private def parseAndRetrivePages(meta: MangaMeta)
    : RIO[zio.http.Client & Scope, (MangaMeta, List[MangaPage])] = {

      import zio.schema.Schema
      import zio.schema.DeriveSchema
      import zio.schema.codec.JsonCodec.*

      given postSchema: Schema[ApiPostResp] = DeriveSchema.gen[ApiPostResp]

      given profileSchema: Schema[ApiProfileResp] = DeriveSchema.gen[ApiProfileResp]

      for
        endpoints <- (
            for 
              galleryUrl <- 
                ZIO.fromEither(URL.decode(meta.galleryUri))

              base <-
                ZIO.attempt:
                    s"${galleryUrl.scheme.get.encode}://${galleryUrl.host.get}${
                      galleryUrl.port.map(i => if (i == 443 || i == 80) "" else s":${i}").getOrElse("")
                    }"
                  .mapError: _ => 
                    ParsingError(s"Error extracting base url while parsing '${galleryUrl}'")

              postApiEndpoint <- ZIO.fromEither(URL.decode(meta.galleryUri.replace(base, s"${base}/api/v1/")))
            
              profileApiEndpoint <- ZIO.fromEither(URL.decode(postApiEndpoint.encode.replaceAll("post/(\\d+)", "profile")))

            yield (base, postApiEndpoint, profileApiEndpoint)
          )
          .tapError: e =>
            ZIO.logError(s"${e.getMessage}. => abort.")
        
        (base, postApiEndpoint, profileApiEndpoint) = endpoints

        client<- ZIO.service[Client]
        
        _ <- ZIO.log(s"Requesting api: '${postApiEndpoint}'.")
        
        postMeta <- client.request:
            configureRequest(postApiEndpoint)
          .flatMap: resp =>
            if (resp.status.code == 403)
              ZIO.fail(BypassError(s"Anti-robots blocking presents while parsing: '${postApiEndpoint}'"))
            else if (resp.status.code == 404)
              ZIO.fail(ParsingError(s"404 not found presents while parsing: '${postApiEndpoint}'"))
            else if (resp.status.code > 500)
              ZIO.fail(ServerError(s"50x presents while parsing: '${postApiEndpoint}'")) 
            else resp.body.to[ApiPostResp]
          .tapError:
            case bp: (BypassError | ParsingError | DecodeError) =>
              ZIO.logError(s"${bp.getMessage}. => abort.")
            case t =>
              ZIO.logError(s"Exception is raised when parsing: '${postApiEndpoint}': ${t.getMessage}")
          .defaultRetry
          .tapSomeError:
            case e if !e.isInstanceOf[BypassError | ParsingError | DecodeError] => 
              ZIO.logError(s"Maximum number of retries has been reached while parsing: '${postApiEndpoint}'. => abort.")
          .map(_.post)

        attachs = 
          (postMeta.file :: postMeta.attachments).distinct.map: mt =>
            DefiniteFileMeta(mt.name.getOrElse(mt.path.substring(mt.path.lastIndexOf('/') + 1)), mt.path)

        parsedPages =
          (
            if (config.download.ignoreArchive)
              attachs.filterNot: e => 
                e.name.endsWith(".zip") || 
                e.name.endsWith(".rar") || 
                e.name.endsWith(".7z")
            else if (
              config.download.ignoreLeftIfArchiveExists && 
              attachs.exists(e => 
                e.name.endsWith(".zip") || 
                e.name.endsWith(".rar") || 
                e.name.endsWith(".7z")
              )
            )
              attachs.filter: e => 
                e.name.endsWith(".zip") || 
                e.name.endsWith(".rar") || 
                e.name.endsWith(".7z")
            else
              attachs
          )
          .zipWithIndex
          .map: (ath, index) =>
            MangaPage(
              0,
              meta.id,
              s"${base}${ath.path}",
              index + 1,
              ath.name.filtered,
              MangaPage.State.Pending.code,
            )

        _ <- ZIO.when(parsedPages.length < 1)(
          ZIO.fail(ParsingError(s"No available attachments were found while parsing: '${postApiEndpoint}'"))
        )

        _ <- ZIO.log(s"Requesting api: '${profileApiEndpoint}'.")

        profileMeta <- client.request:
            configureRequest(profileApiEndpoint)
          .flatMap: resp =>
            resp.status.code match 
            case 403 =>
              ZIO.fail(BypassError(s"Anti-bots blocking presents when parsing: '${profileApiEndpoint}'"))
            case 404 =>
              ZIO.fail(ParsingError(s"404 not found presents when parsing: '${profileApiEndpoint}'"))
            case i if i > 500 =>
              ZIO.fail(ServerError(s"50x presents presents when parsing: '${profileApiEndpoint}'"))
            case _ =>
              resp.body.to[ApiProfileResp]
          .tapError:
            case bp: (BypassError | ParsingError | DecodeError) =>
              ZIO.logError(s"${bp.getMessage}. => abort.")
            case t =>
              ZIO.logError(s"Exception is raised when parsing: '${profileApiEndpoint}': ${t.getMessage}")
          .defaultRetry
          .tapSomeError:
            case e if !e.isInstanceOf[BypassError | ParsingError | DecodeError] => 
              ZIO.logError(s"Maximum number of retries has been reached while parsing: '${profileApiEndpoint}'. => abort.")

        title = 
          config.titlePattern
            .replace(":title", postMeta.title)
            .replace(":artist", profileMeta.name)
            .replace(":service", postMeta.service)
            .replace(":time", postMeta.published.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")))
            .replace(":date", postMeta.published.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .filtered

        parsedMeta = 
          meta.copy(
            title = Some(title), 
            totalPages = parsedPages.length, 
            isParsed = true
          )
        
      yield (parsedMeta, parsedPages)
    }


  private def downloadSinglePage(title: String, page: MangaPage): RIO[zio.http.Client & Scope, Long] = {
    for
      pageUrl <-
        ZIO.fromEither:
            URL.decode(page.pageUri)
          .tapError: e =>
              ZIO.logError(s"${e.getMessage}. => abort.")

      _ <- ZIO.log(s"Downloading: '${pageUrl}'")

      _client <- ZIO.service[Client]

      client = 
        _client @@ ZClientAspect.followRedirects(2)((resp, message) => 
          ZIO.logError(s"Redirect Error: ${message}").as(resp)
        )

      cfile = s"${cachePath}/${UUID.randomUUID().toString()}.tmp"

      fireSink =
        ZSink.collectAllN[Byte](20).mapZIO: chk =>
          ZIO.when(String(chk.toArray).startsWith("<html>"))(
            ZIO.fail(ServerError(s"Unexpected html presents when downloading: '${pageUrl}'")) 
          )

      length <-
        client
          .request(configureRequest(pageUrl))
        .flatMap: resp =>
          resp.status.code match 
            case 403 =>
              ZIO.fail(BypassError(s"Anti-bots blocking presents when downloading: '${pageUrl}'"))
            case 404 =>
              ZIO.fail(ParsingError(s"404 not found presents when downloading: '${pageUrl}'"))
            case i if i > 500 =>
              ZIO.fail(ServerError(s"50x presents presents when downloading: '${pageUrl}'"))
            case _ =>
              resp.body.asStream.tapSink(fireSink).timeout(4 hours).run(ZSink.fromFileName(cfile))
        .onInterrupt: _ =>
          defaultRemoveFile(cfile).forkDaemon
        .tapError:
          case bp: (BypassError | ParsingError) =>
            ZIO.logError(s"${bp.getMessage}. => abort.")
          case t =>
            ZIO.logError(s"Exception is raised when downloading: '${pageUrl}': ${t.getMessage}")
        .defaultRetry
        .tapSomeError:
          case e if !e.isInstanceOf[BypassError | ParsingError] => 
            ZIO.logError(s"Maximum number of retries has been reached while downloading: '${pageUrl}'. => abort.")
              *> defaultRemoveFile(cfile).forkDaemon

      savedPath = s"${downPath}/${title}/${page.title}"

      _ <- ZIO.attemptBlockingIO(Files.createDirectories(Paths.get(s"${downPath}/${title}")))
        
      _ <- defaultMoveFile(cfile, savedPath)

      _ <- ZIO.log(s"Saved: '${pageUrl}' as '${savedPath}', downloaded size: ${String.format("%.2f", length.toFloat / 1024 / 1024)} MB")
      
    yield length
  }

  end downloadSinglePage




  def handle(meta: MangaMeta): URIO[zio.http.Client & Scope, Unit] =
  (
    for
      _ <- idleIndicator.setIdle(false)
      _ <-
        ZIO.whenCaseDiscard(meta.isParsed) {
          case true =>
            Ref.make(meta.completedPages).flatMap: cp =>
              ZStream.fromIterable(meta.completedPages to meta.totalPages)
                .runForeach(_ =>
                    taskHelper.acquirePage(meta.id).flatMap:
                      case Some(p) =>
                        downloadSinglePage(meta.title.get, p)
                          .tapError: e =>
                            taskHelper.failPage(p.id)
                          .flatMap: _ =>
                            taskHelper.completePage(meta.id, p.id)
                      case None =>
                        ZIO.ifZIO(taskHelper.checkMetaExists(meta.id))(
                          onTrue =
                            taskHelper.completeManga(meta.id)
                              *> ZIO.log(s"Completed: '${meta.galleryUri}' as '${meta.title.get}'")
                          ,
                          onFalse = ZIO.fail(LostMetaError(s"Meta (id = ${meta.id}) may be removed"))
                        )
                  )
                *> idleIndicator.setIdle(true)
          case false => 
            for
              t <- parseAndRetrivePages(meta)
              (m, ps) = t
              _ <- taskHelper.submitParsed(m, ps)
              _ <- ZIO.log(s"Parsed: '${m.galleryUri}' as '${m.title.get}'")
              _ <- handle(m)
            yield ()
        }
      _ <- ZIO.sleep(3 seconds)
    yield ()
  )
  .tapError: 
    case l: LostMetaError =>
      ZIO.logError(s"KemonoHandler: ${l.message}. => abort.")
    case e =>
      taskHelper.failManga(meta.id, e.getMessage())
  .catchAll: _ =>
    idleIndicator.setIdle(true)
  .onError: cause =>
    ZIO.logError(s"KemonoHandler: ${cause.dieOption.map(_.getMessage()).getOrElse("???")}. => die.")
  
  end handle