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

import scala.concurrent.duration.DurationInt

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption




object NHentaiHandler:

  object NHentaiConfig:
  
    final case class CloudflareConfig(

      strategy: String,

      providedCookies: String
    )


    final case class ParsingConfig(

      implementation: String
    )


    final case class DownloadConfig(

      parallelPages: Int,

      fileSignatureCheck: FileSignatureCheckConfig,
    )


    final case class FileSignatureCheckConfig(

      enabled: Boolean,

      fallbackExtensionName: String
    )
    

    given Config[NHentaiConfig] = deriveConfig[NHentaiConfig]

    def load =
      ConfigProvider.fromHoconFile(new java.io.File("config/nhentai.conf"))
        .load(deriveConfig[NHentaiConfig].mapKey(toKebabCase))
        .tapError(e => ZIO.logError(e.getMessage()))

    val layer = ZLayer.fromZIO(load)
    

  end NHentaiConfig
  

  import NHentaiConfig.*

  final case class NHentaiConfig(

    agent: Option[String],

    patterns: List[String],

    cloudflare: CloudflareConfig,

    parsing: ParsingConfig,

    download: DownloadConfig
  )

  sealed trait NHentaiError:
    val message: String

  final case class BypassError(message: String) extends NHentaiError

  final case class ParsingError(message: String) extends NHentaiError

  final case class StreamingError(message: String) extends NHentaiError

  final case class ServerError(message: String) extends NHentaiError

  final case class FileSystemError(message: String) extends NHentaiError



  trait IdleIndicator:

    def isIdle: UIO[Boolean]

    def setIdle(value: Boolean): UIO[Unit]
  
  end IdleIndicator

  
  final class IdleIndicatorLive(ref: Ref[Boolean]) extends IdleIndicator: 

    def isIdle = ref.get

    def setIdle(value: Boolean) = ref.set(value)

  end IdleIndicatorLive


  val layer: ZLayer[TaskHelper & AppConfig & CommonConfig, Nothing, NHentaiHandler] =
    ZLayer {
      for
        nhentaiConfig <- NHentaiConfig.load.orDie
        appConfig <- ZIO.service[AppConfig]
        commonConfig <- ZIO.service[CommonConfig]
        taskHelper <- ZIO.service[TaskHelper]
        idleIndicator <- Ref.make(true).map(IdleIndicatorLive(_))
      yield NHentaiHandler(nhentaiConfig, appConfig.downPath, commonConfig.defaultUserAgent, idleIndicator, taskHelper)
    }
    

end NHentaiHandler



extension [A <: Element] (element: A)
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


private def processFileExtensionName(bytes: List[Byte]): Either[List[Byte], String] =
  bytes match
    case -1 :: -40 :: -1 :: _ => Right("jpg")
    // 0x89 = 1000 1001 -> 1111 0110 -> 1111 0110 + 1b = 1111 0111 = -119
    //case -119 :: 80 :: 78 :: 71 :: 13 :: 10 :: 26 :: 10 :: Nil => "png"
    case -119 :: 80 :: 78 :: _ => Right("png")
    // 0x47 = 0100 0111 -> self = 71
    case 71 :: 73 :: 70 :: _ => Right("gif")
    //52 49 46 46
    case 82 :: 73 :: 70 :: 70 :: _ => Right("webp")
    
    case _ => Left(bytes)


final class NHentaiHandler(

  config: NHentaiHandler.NHentaiConfig,

  downPath: String,

  defaultUserAgent: String,

  idleIndicator: NHentaiHandler.IdleIndicator,

  taskHelper: TaskHelper,

) extends moe.karla.handler.Handler:


  def canHandle(url: String) =
    ZIO.succeed(config.patterns.exists(url.matches(_)))


  def isIdle = idleIndicator.isIdle


  private def setIdle(value: Boolean) = idleIndicator.setIdle(value)


  private def configureRequest(uri: String): IO[UnsupportedOperationException, Request] =
    if (config.cloudflare.strategy == "provided-cookies")
      ZIO.succeed:
        Request.get(uri)
          .addHeader("Cookie", config.cloudflare.providedCookies)
          .removeHeader("User-Agent")
          .addHeader("User-Agent", config.agent.getOrElse(defaultUserAgent))
          .addHeader(AcceptEncoding(AcceptEncoding.GZip(), AcceptEncoding.Deflate()))
    else
      ZIO.fail:
        UnsupportedOperationException("Current strategy for bypassing cloudflare is not implemented")
  

  import NHentaiHandler.*
  
    
  private val retryPolicy = 
    Schedule.recurs(4) && 
    Schedule.spaced(3 seconds) && 
    Schedule.fibonacci(700 millis)


  extension [R, E <: Throwable | NHentaiError, A] (mo: ZIO[R, E, A])
    private def defaultRetry = 
      mo.retry(retryPolicy && Schedule.recurWhile[Throwable | NHentaiError] {
        case _: (BypassError | ParsingError) => false
        case _ => true
      })



  private def defaultMoveFile(src: String, to: String) =
    ZIO.attemptBlockingIO(
      Files.move(
        Paths.get(src), 
        Paths.get(to), 
        StandardCopyOption.REPLACE_EXISTING
      )
    )
    .retry(Schedule.recurs(3) && Schedule.spaced(2 seconds))




  private def parseAndRetrivePages(meta: MangaMeta) = 
    (
      for
        _ <- ZIO.log(s"Parsing: '${meta.galleryUri}'")

        req <- configureRequest(meta.galleryUri)

        client <- ZIO.service[Client]

        body <- 
          client.request(req)
            .flatMap { resp =>
              if (resp.status.code == 403)
                ZIO.fail(BypassError(s"Cloudflare blocking presents while parsing: '${meta.galleryUri}'"))
              else if (resp.status.code == 404)
                ZIO.fail(ParsingError(s"404 not found presents while parsing: '${meta.galleryUri}'"))
              else if (resp.status.code > 500)
                ZIO.fail(ServerError(s"50x presents while parsing: '${meta.galleryUri}'")) 
              else resp.body.asString
            }
            .tapSomeError:
              case ServerError(msg) => 
                ZIO.logError(s"${msg}.")
              case t: Throwable =>
                ZIO.logError(s"Unexpected exception is raised while parsing: '${meta.galleryUri}': ${t.getMessage()}")
            .defaultRetry
            .tapSomeError:
              case e if !e.isInstanceOf[BypassError | ParsingError] => 
                ZIO.logError(s"Maximum number of retries has been reached while parsing: '${meta.galleryUri}'. => abort.")
            .map:
              Jsoup.parse(_).body
            

        titleString <- ZIO.fromOption(
          Option(body.selectFirst("h2.title")).orElse(Option(body.selectFirst("h1.title"))).map(_.wholeText)
        ).mapError(_ => ParsingError(s"Extracting title failed while parsing '${meta.galleryUri}'"))

        pages <- ZIO.attempt(
          body.select("span.tags > a.tag > span.name").last.text.toInt
        ).mapError(_ => ParsingError(s"Extracting pages failed while parsing '${meta.galleryUri}'"))


        parsedMeta = meta.copy(title = Some(titleString), isParsed = true, totalPages = pages)


        parsedPages = (1 to parsedMeta.totalPages).map(p =>

            val u = if (parsedMeta.galleryUri.last == '/') parsedMeta.galleryUri.dropRight(1) else parsedMeta.galleryUri

            MangaPage(
              0,
              parsedMeta.id,
              s"${u}/${p}/",
              p,
              s"${titleString.filtered}",
              MangaPage.State.Pending.code,
            )
          )
          .toList

      yield (parsedMeta, parsedPages)
    )
    .tapSomeError:
      case e: (BypassError | ParsingError) => 
        ZIO.logError(s"${e.message}. => abort.")
      case e: UnsupportedOperationException =>
        ZIO.logError(s"${e.getMessage()}. => abort.")
    
  
  end parseAndRetrivePages


  
  private def downloadSinglePage(page: MangaPage) = 
    (
      for
        _ <- ZIO.log(s"Parsing: '${page.pageUri}'")

        req <- configureRequest(page.pageUri)

        client <- ZIO.service[Client]

        body <- 
          client.request(req)
            .flatMap { resp =>
              if (resp.status.code == 403) 
                ZIO.fail(BypassError(s"Cloudflare blocking presents while parsing: '${page.pageUri}'"))
              else if (resp.status.code == 404)
                ZIO.fail(ParsingError(s"404 not found presents while parsing: '${page.pageUri}'"))
              else if (resp.status.code > 500)
                ZIO.fail(ServerError(s"50x presents while trying to download: '${page.pageUri}'")) 
              else resp.body.asString
            }
            .map(Jsoup.parse(_).body)

        imgUri <- ZIO.attempt(
          body.select("section#image-container > a > img").first.attr("src")
        ).mapError(_ => ParsingError(s"Extracting image uri failed while parsing '${page.pageUri}'"))


        ref <- Ref.make(Either.cond[List[Byte], String](true, "unknownfile", List[Byte]()))

        fireSink = ZSink.collectAllN[Byte](6).map(_.toList).mapZIO(bytes =>
          ref.set:
            if(config.download.fileSignatureCheck.enabled)
              processFileExtensionName(bytes)
            else
              Right(imgUri.substring(imgUri.lastIndexOf('.') + 1))
        )

        _ <- ZIO.attemptBlockingIO(Files.createDirectories(Paths.get(s"${downPath}/${page.title}")))

        path = s"${downPath}/${page.title}/${page.pageNumber}"

        _ <- ZIO.log(s"Downloading: '${imgUri}'")

        length <- 
          client.request(
              Request.get(imgUri).addHeader(AcceptEncoding(AcceptEncoding.GZip(), AcceptEncoding.Deflate()))
            )
            .flatMap { resp =>
              if (resp.status.code == 403)
                ZIO.fail(BypassError(s"Cloudflare blocking presents while trying to download: '${imgUri}'"))
              else if (resp.status.code == 404)
                ZIO.fail(ParsingError(s"404 not found presents while trying to download: '${imgUri}'"))
              else if (resp.status.code > 500)
                ZIO.fail(ServerError(s"50x presents while trying to download: '${imgUri}'")) 
              else ZIO.succeed(resp.body.asStream)
            }
            .flatMap(_.timeout(120 seconds).tapSink(fireSink).run(ZSink.fromFileName(path)))
            .mapError: 
              case e: (BypassError | ParsingError | ServerError) => e
              case _ =>
                StreamingError(s"Streaming error presents while trying to download: '${imgUri}'")
                


        preExt <- ref.get

        _ <- ZIO.whenCaseDiscard(preExt):
          case Left(list) =>
            ZIO.logWarning(s"Unknown file signature is detected, " +
              s"signature: '${list.map(String.valueOf(_)).reduce((a, b) => s"${a},${b}")}', " +
              s"page uri: '${page.pageUri}', " +
              s"image uri: '${imgUri}', " +
              s"extension name falls back to '${config.download.fileSignatureCheck.fallbackExtensionName}'")

        ext = preExt.getOrElse(config.download.fileSignatureCheck.fallbackExtensionName)

        _ <- defaultMoveFile(path, s"${path}.${ext}")

        _ <- ZIO.log(s"Saved: '${imgUri}' as '${path}.${ext}', downloaded size: ${String.format("%.2f", length.toFloat / 1024)} KB")
        
      yield length
    )
    .tapError: 
      case bp: (BypassError | ParsingError) =>
        ZIO.logError(s"${bp.message}. => abort.")
      case se : (StreamingError | ServerError) =>
        ZIO.logError(s"${se.message}.")
      case e: UnsupportedOperationException =>
        ZIO.logError(s"${e.getMessage()}. => abort.")
      case t: Throwable =>
        ZIO.logError(s"Unexpected exception is raised when downloading: '${page.pageUri}': ${t.getMessage()}")
    .defaultRetry
    .tapSomeError:
      case e if !e.isInstanceOf[BypassError | ParsingError | UnsupportedOperationException] => 
        ZIO.logError(s"Maximum number of retries has been reached while parsing: '${page.pageUri}'. => abort.")

  end downloadSinglePage



  def handle(meta: MangaMeta): URIO[zio.http.Client & Scope, Unit] =
    (
      for
        _ <- idleIndicator.setIdle(false)
        _ <-
          ZIO.whenCaseDiscard(meta.isParsed) {
            case true =>
              ZStream
                .fromIterable(0 until (meta.totalPages - meta.completedPages + 1))
                .mapZIOParUnordered(config.download.parallelPages)(_ =>
                  (
                    for
                      op <- taskHelper.acquirePage(meta.id)
                      _ <- op.map(p =>
                        (
                          downloadSinglePage(p).tapError: _ =>
                            taskHelper.failPage(p.id)
                        )
                        *> taskHelper.completePage(meta.id, p.id)
                      )
                      .getOrElse(ZIO.succeed(false))
                    yield ()
                  )
                )
                .runDrain
                *> taskHelper.completeManga(meta.id)
                *> ZIO.log(s"Completed: '${meta.galleryUri}'.")
            case false =>
              for
                t <- parseAndRetrivePages(meta)
                (m, ps) = t
                _ <- taskHelper.submitParsed(m, ps)
                _ <- handle(m)
              yield ()
          }
          .tapSomeError {
            case n: NHentaiError => taskHelper.failManga(meta.id, n.message)
            case e: Throwable => taskHelper.failManga(meta.id, e.getMessage())
          }
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
      ZIO.logError(s"NHentaiHandler: ${cause.dieOption.map(_.getMessage()).getOrElse("???")}. => die.")