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
import zio.http.Header.AcceptEncoding


import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import org.apache.commons.compress.archivers.examples.Expander

import scala.concurrent.duration.DurationInt

import java.nio.file.*
import java.sql.SQLException
import java.util.UUID
import java.net.MalformedURLException




object HentaiMangaHandler:
  
  object HentaiMangaConfig:
    
    final case class DownloadConfig(

      decompress: Boolean
    )

    def load =
      ConfigProvider.fromHoconFile:
          new java.io.File("config/hentaimanga.conf")
        .load:
          deriveConfig[HentaiMangaConfig].mapKey(toKebabCase)
        .tapError:
          e => ZIO.logError(e.getMessage())


    def layer = ZLayer.fromZIO(load)
    

  end HentaiMangaConfig
  

  import HentaiMangaConfig.DownloadConfig


  final case class HentaiMangaConfig(

    agent: Option[String],

    patterns: List[String],

    download: DownloadConfig
  )


  given Config[HentaiMangaConfig] = deriveConfig[HentaiMangaConfig]


  final case class ParsingError(message: String) extends RuntimeException(message)

  final case class ServerError(message: String) extends RuntimeException(message)

  final case class BypassError(message: String) extends RuntimeException(message)


  trait IdleIndicator:

    def isIdle: UIO[Boolean]

    def setIdle(value: Boolean): UIO[Unit]
  
  end IdleIndicator


  final class IdleIndicatorLive(ref: Ref[Boolean]) extends IdleIndicator: 

    def isIdle = ref.get

    def setIdle(value: Boolean) = ref.set(value)

  end IdleIndicatorLive



  val layer: ZLayer[TaskHelper & AppConfig & CommonConfig, Nothing, HentaiMangaHandler] =
    ZLayer {
      for
        hentaiMangaConfig <- HentaiMangaConfig.load.orDie
        appConfig <- ZIO.service[AppConfig]
        commonConfig <- ZIO.service[CommonConfig]
        taskHelper <- ZIO.service[TaskHelper]
        idleIndicator <- Ref.make(true).map(IdleIndicatorLive(_))
      yield 
        HentaiMangaHandler(
          hentaiMangaConfig, 
          appConfig.downPath, 
          appConfig.cachePath, 
          commonConfig.defaultUserAgent, 
          idleIndicator, 
          taskHelper
        )
    }
    

end HentaiMangaHandler


extension[A <: Element] (element: A)
  private def map[B](f: A => B): B = f(element)


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


final class HentaiMangaHandler(
  
  config: HentaiMangaHandler.HentaiMangaConfig,

  downPath: String,
  
  cachePath: String,

  defaultUserAgent: String,

  idleIndicator: HentaiMangaHandler.IdleIndicator,

  taskHelper: TaskHelper,

) extends moe.karla.handler.Handler:

  def canHandle(url: String) =
    ZIO.succeed(config.patterns.exists(url.matches(_)))


  def isIdle = idleIndicator.isIdle
  


  private val expander = new Expander


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
  

  import HentaiMangaHandler.*

  
  private val retryPolicy = 
    Schedule.recurs(4) && 
    Schedule.spaced(6 second) && 
    Schedule.fibonacci(800 millis)


  extension [R, E <: Throwable, A] (mo: ZIO[R, E, A])
    private def defaultRetry = 
      mo.retry(retryPolicy && Schedule.recurWhile[Throwable] {
        case _: (BypassError | ParsingError) => false
        case _ => true
      })



  private def parseAndRetrivePages(meta: MangaMeta)
    : RIO[zio.http.Client & Scope, (MangaMeta, List[MangaPage])] =
    for
      client <- ZIO.service[Client]

      _ <- ZIO.log(s"Parsing: '${meta.galleryUri}'")

      url <- ZIO.fromEither(URL.decode(meta.galleryUri))
      
      body <-
        client.request:
            configureRequest(url)
          .flatMap: resp =>
            if (resp.status.code == 403) 
              ZIO.fail(BypassError(s"Cloudflare blocking presents while parsing: '${url}'"))
            else if (resp.status.code == 404)
              ZIO.fail(ParsingError(s"404 not found presents while parsing: '${url}'"))
            else if (resp.status.code > 500)
              ZIO.fail(ServerError(s"50x presents while parsing: '${url}'")) 
            else resp.body.asString
          .tapSomeError:
            case e if !e.isInstanceOf[BypassError | ParsingError] => 
              ZIO.logError(s"Exception is raised when parsing: '${url}': ${e.getMessage}")
          .defaultRetry
          .tapSomeError:
            case e if !e.isInstanceOf[BypassError | ParsingError] =>
              ZIO.logError(s"Maximum number of retries has been reached while parsing: '${url}'. => abort.")
          .map:
            Jsoup.parse(_).body

      titleString <- 
        ZIO.fromOption:
            Option(body.selectFirst("div#bodywrap > h2"))
              .map(_.wholeText.filtered)
              .collect { case s: String if s.size > 0 => s }
          .mapError: _ => 
            ParsingError(s"Extracting title failed while parsing '${url}'")


      downloadPage <- 
        ZIO
          .fromOption:
            Option(body.selectFirst("div.download_btns > a.btn")).map(_.attr("href")).map(path => 
              s"${url.scheme.get.encode}://${url.host.get}${path}")
          .mapError: _ => 
            ParsingError(s"Extracting download page failed while parsing '${url}'")


      parsedMeta = meta.copy(title = Some(titleString), totalPages = 1, isParsed = true)

      parsedPages =
        List:
          MangaPage(
            0,
            parsedMeta.id,
            downloadPage,
            1,
            s"${titleString.filtered}",
            MangaPage.State.Pending.code,
          )
      
    yield (parsedMeta, parsedPages)

  end parseAndRetrivePages



  private def downloadSinglePage(page: MangaPage): RIO[zio.http.Client & Scope, Long] = {

    def processFileExtensionName(bytes: List[Byte]): Either[List[Byte], String] =
      bytes match
        //0x50 = 0101 0000 -> self = 80
        //0x4B = 0100 1011 -> self = 75
        //0x03 = 0000 0011 -> self = 3
        case 80 :: 75 :: 3 :: _ => Right("zip")
        //0x52 = 0101 0010 -> self = 82
        //0x61 = 0110 0001 -> self = 97
        //0x72 = 0111 0010 -> self = 114
        case 82 :: 97 :: 114 :: _ => Right("rar")
        //0x37 = 0011 0110 -> self = 55
        //0x7A = 0111 1010 -> self = 122
        //0xBC = 1011 1100 -> 1100 0011 -> 1100 0100 = -68
        case 55 :: 122 :: -68 :: _ => Right("7z")
        
        case _ => Left(bytes)

    end processFileExtensionName

    for
      _ <- ZIO.log(s"Parsing: '${page.pageUri}'")

      pageUrl <- ZIO.fromEither(URL.decode(page.pageUri))

      client <- ZIO.service[Client]

      body <-
        client
          .request(configureRequest(pageUrl))
        .flatMap: resp =>
          if (resp.status.code == 403) 
            ZIO.fail(BypassError(s"Cloudflare blocking presents while parsing: '${pageUrl}'"))
          else if (resp.status.code == 404)
            ZIO.fail(ParsingError(s"404 not found presents while parsing: '${pageUrl}'"))
          else if (resp.status.code > 500)
            ZIO.fail(ServerError(s"50x presents presents while parsing: '${pageUrl}'")) 
          else resp.body.asString
        .tapSomeError:
          case e if !e.isInstanceOf[BypassError | ParsingError] => 
            ZIO.logError(s"Exception is raised when parsing: '${pageUrl}': ${e.getMessage()}")
        .defaultRetry
        .tapSomeError:
          case e if !e.isInstanceOf[BypassError | ParsingError] => 
            ZIO.logError(s"Maximum number of retries has been reached while parsing: '${pageUrl}'. => abort.")
        .map:
          Jsoup.parse(_).body

      archiveUri <- 
        ZIO.attempt(
          body.select("div#adsbox > a.down_btn").first.attr("href")
        )
        .map(uri => s"https:${uri}")
        .map(uri => uri.substring(0, uri.indexOf("?n=")))
        .mapError: _ => 
          ParsingError(s"Extracting archive uri from page failed while parsing '${page.pageUri}'")
        .tapError: e =>
          ZIO.logError(s"${e.getMessage()}. => abort.")


      ref <- Ref.make(Either.cond[List[Byte], String](true, "unknownfile", List[Byte]()))

      fireSink = ZSink.collectAllN[Byte](6).map(_.toList).mapZIO(bytes => ref.set(processFileExtensionName(bytes)))

      cfile = s"${cachePath}/${UUID.randomUUID().toString()}.tmp"

      _ <- ZIO.log(s"Downloading: '${archiveUri}'")

      length <- 
        client.request(Request.get(archiveUri))
          .flatMap { resp =>
            if (resp.status.code == 404)
              ZIO.fail(ParsingError(s"404 not found presents while trying to download: '${archiveUri}'"))
            else if (resp.status.code > 500)
              ZIO.fail(ServerError(s"50x presents while trying to download: '${archiveUri}'")) 
            else ZIO.succeed(resp.body.asStream)
          }
          .flatMap(_.timeout(6 hours).tapSink(fireSink).run(ZSink.fromFileName(cfile)))
          .onInterrupt: _ =>
            defaultRemoveFile(cfile).forkDaemon
          .tapSomeError:
            case e if !e.isInstanceOf[ParsingError] => 
              ZIO.logError(s"Exception is raised when parsing: '${archiveUri}': ${e.getMessage()}")
          .defaultRetry
          .tapSomeError:
            case e if !e.isInstanceOf[ParsingError] => 
              ZIO.logError(s"Maximum number of retries has been reached while downloading: '${archiveUri}'. => abort.")
                *> defaultRemoveFile(cfile).forkDaemon


      preExt <- ref.get

      _ <- ZIO.when(preExt.isLeft)(
        ZIO.logWarning(s"Unknown file signature is detected, archive uri: '${archiveUri}', page uri: '${page.pageUri}'")
      )

      ext = preExt.getOrElse("unknownfile")

      savedPathRef <- Ref.make(s"${downPath}/${page.title}.${ext}")

      _ <-
        if (config.download.decompress) {
          ext match
            case "zip" => 
              ZIO.attemptBlockingIO:
                  expander.expand(Paths.get(cfile), Paths.get(s"${downPath}/${page.title}"))
                .catchAll: _ => 
                  ZIO.logWarning(s"Decompress failure presents: '${cfile}', fall back to compressed file.") *>
                    savedPathRef.get.flatMap: p =>
                      defaultMoveFile(cfile, p)
                .flatMap: _ =>
                  ZIO.attemptBlockingIO(Files.deleteIfExists(Paths.get(cfile))) *>
                    savedPathRef.set(s"${downPath}/${page.title}")
            case _ =>
              ZIO.logWarning(s"Decompress supports only zip archives currently: '${cfile}'") *>
                savedPathRef.get.flatMap: p =>
                  defaultMoveFile(cfile, p)
        }
        else
          savedPathRef.get.flatMap: p =>
            defaultMoveFile(cfile, p)
      
      savedPath <- savedPathRef.get

      _ <- ZIO.log(s"Saved: '${archiveUri}' as '${savedPath}', downloaded size: ${String.format("%.2f", length.toFloat / 1024 / 1024)} MB")
      
    yield length
  }

  end downloadSinglePage




  def handle(meta: MangaMeta): URIO[zio.http.Client & Scope, Unit] =
  (
    for
      _ <- idleIndicator.setIdle(false)
      _ <-
        ZIO.whenCaseDiscard(meta.isParsed) {
          case true => {
            for
              p <- taskHelper.acquirePage(meta.id).map(_.get)
              _ <- 
                downloadSinglePage(p).tapError: _ =>
                  taskHelper.failPage(p.id)
              _ <- taskHelper.completePage(meta.id, p.id)
            yield ()
          }
          case false => {
            for
              t <- parseAndRetrivePages(meta)
              (m, ps) = t
              _ <- taskHelper.submitParsed(m, ps)
              p <- taskHelper.acquirePage(meta.id).map(_.get)
              _ <-
                downloadSinglePage(p).tapError: _ =>
                  taskHelper.failPage(p.id)
              _ <- taskHelper.completePage(meta.id, p.id)
            yield ()
          }
        }
        .tapError: e => 
          taskHelper.failManga(meta.id, e.getMessage())
        .tapSomeError:
          case e: (BypassError | ParsingError | MalformedURLException) => 
            ZIO.logError(s"${e.getMessage()}'. => abort.")

      _ <- taskHelper.completeManga(meta.id)

      _ <- ZIO.sleep(8 seconds)

      _ <- idleIndicator.setIdle(true)
    yield ()
  )
  .catchAll: _ =>
    idleIndicator.setIdle(true)
  /*
    .onError { cause =>
      val msg = cause.dieOption.map(_.getMessage()).getOrElse("???")
      taskHelper.failManga(meta.id, msg).ignore
        *> ZIO.logError(s"Fatal error: ${msg}. => die.")
    }
  */
  .onError: cause =>
    ZIO.logError(s"HentaiMangaHandler: ${cause.dieOption.map(_.getMessage()).getOrElse("???")}. => die.")
  
  end handle