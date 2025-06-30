package moe.karla.handler.default


import moe.karla.config.AppConfig
import moe.karla.service.TaskHelper
import moe.karla.metadata.*


import zio.*
import zio.stream.*

import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.deriveConfig
import zio.config.refined.*

import eu.timepit.refined.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.*

import zio.http.*
import zio.http.Client
import zio.http.Request
import zio.http.Header.SetCookie

import zio.json.*

import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill

/*
import com.microsoft.playwright.*
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.Browser.NewPageOptions
import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.options.ViewportSize
*/

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import scala.concurrent.duration.DurationInt

import java.io.IOException
import java.nio.file.*
import java.sql.SQLException
import java.util.UUID
import java.net.MalformedURLException




object MasiroHandler:

  object MasiroConfig:

    final case class PasringConfig(implementation: String)

    final case class PaymentConfig(autoPay: Boolean, minimumBalance: Int Refined GreaterEqual[1])

    def load =
      ConfigProvider.fromHoconFile:
          new java.io.File("config/masiro.conf")
        .load:
          deriveConfig[MasiroConfig].mapKey(toKebabCase)
        .tapError: e => 
          ZIO.logError(e.getMessage)

  end MasiroConfig


  import MasiroConfig.*


  final case class MasiroConfig(

    novelViewPatterns: List[String],

    chapterPatterns: List[String],

    defaultLocation: String,

    agent: Option[String],

    providedCookies: String,

    skipChargeableChapter: Boolean,

    cooldown: Duration,

    payment: PaymentConfig,

    parsing: PasringConfig,
  )


  final case class ParsingError(message: String) extends RuntimeException(message)

  final case class ServerError(message: String) extends RuntimeException(message)

  final case class BypassError(message: String) extends RuntimeException(message)

  final case class InsufficientBalanceError(message: String) extends RuntimeException(message)

  final case class LostMetaError(message: String) extends RuntimeException(message)


  trait IdleIndicator:

    def isIdle: UIO[Boolean]

    def setIdle(value: Boolean): UIO[Unit]
  
  end IdleIndicator

  
  final class IdleIndicatorLive(ref: Ref[Boolean]) extends IdleIndicator: 

    def isIdle = ref.get

    def setIdle(value: Boolean) = ref.set(value)

  end IdleIndicatorLive


  trait RequestCookiesHolder:

    def getCookies: UIO[Chunk[Cookie.Request]]

    def setCookies(cookies: Chunk[Cookie.Request]): UIO[Unit]
  
  end RequestCookiesHolder

  
  final class RequestCookiesHolderLive(cookiesRef: Ref[Chunk[Cookie.Request]]) extends RequestCookiesHolder:

    def getCookies: UIO[Chunk[Cookie.Request]] = cookiesRef.get

    def setCookies(cookies: Chunk[Cookie.Request]): UIO[Unit] = 
      cookiesRef.set(cookies)


  val layer =
    ZLayer {
      for 
        masiroConfig <- MasiroConfig.load.orDie
        appConfig <- ZIO.service[AppConfig]
        commonConfig <- ZIO.service[CommonConfig]
        initCookies <- ZIO.fromEither(Cookie.decodeRequest(masiroConfig.providedCookies)).orDie
        idleIndicator <- Ref.make(true).map(IdleIndicatorLive(_))
        cookiesHolder <- Ref.make(initCookies).map(RequestCookiesHolderLive(_))
        taskHelper <- ZIO.service[TaskHelper]
      yield 
        MasiroHandler(
          masiroConfig,
          appConfig.downPath,
          commonConfig.defaultUserAgent,
          idleIndicator,
          cookiesHolder,
          taskHelper,
        )
    }

  
  final case class NovelMeta(
    id: Int,
    novel_id: Int,
    title: String,
    cost: Int,
    limit_lv: Int
  )

  given decoder: JsonDecoder[NovelMeta] = DeriveJsonDecoder.gen[NovelMeta]

  final case class PayResponse(
    code: Int,
    msg: String,
  )

end MasiroHandler



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
      .replace(" ", "")
      .replaceAll("[\\\\/:*?\"<>|]", " ")


/*
private def acquirePlaywright: ZIO[Any, IOException, Playwright] =
  ZIO.attemptBlockingIO(Playwright.create())

private def releasePlaywright(playwright: => Playwright): ZIO[Any, Nothing, Unit] =
  ZIO.succeedBlocking(playwright.close())


private def openPlaywright: ZIO[Scope, IOException, Playwright] =
  ZIO.acquireRelease(acquirePlaywright)(releasePlaywright(_))


private def playwrightForCookies = openPlaywright.flatMap { play =>

  object Completed extends RuntimeException

  for 
    content <- ZIO.attemptBlocking {
      val launchOptions = new BrowserType.LaunchOptions()
      launchOptions.headless = false
      launchOptions.chromiumSandbox = true

      val browser = play.chromium.launch(launchOptions)

      val contextOption = new NewContextOptions()

      contextOption.javaScriptEnabled = true
      contextOption.viewportSize = java.util.Optional.of(new ViewportSize(1603, 949))

      browser.newContext(contextOption)
    }
    _ <- ZIO.attemptBlocking(content.addInitScript("""
      Object.defineProperties(navigator, {webdriver:{get:()=>undefined}});
    """))
    
    page <- ZIO.attemptBlocking {
      val page = content.newPage
      page.navigate("https://cn.bing.com")
      page
    }

    _ <- ZIO.sleep(10 seconds)
    _ <- ZIO.attemptBlocking(page.navigate("https://masiro.me"))


    flushed <- Ref.make(false)

    cookiesRef <- Ref.make(Buffer[Cookie]())

    _ <- ZStream.repeatWithSchedule(0, Schedule.spaced(10 seconds)).foreach { _ =>
      
      val cookies = content.cookies("https://masiro.me").asScala

      if (cookies.map(_.name).filter(n =>
          n == "cf_clearance" ||
          n == "laravel_session" ||
          n == "XSRF-TOKEN" ||
          n == "last_check_announcement" ||
          n == "last_check_signin" ||
          n.startsWith("remember_admin")
        ).length >= 6
      )
        flushed.set(true) *> cookiesRef.set(cookies) *> ZIO.fail(Completed)

      else ZIO.unit
    }
    .catchSome:
      case _: Completed.type => ZIO.unit

    cookies <- cookiesRef.get

  yield cookies
}
*/

private val retryPolicy = 
  Schedule.recurs(4) && 
  Schedule.spaced(10 seconds) && 
  Schedule.fibonacci(700 millis)


import MasiroHandler.*


extension [R, E <: Throwable, A] (mo: ZIO[R, E, A])
  private def defaultRetry = 
    mo.retry(retryPolicy && Schedule.recurWhile[Throwable] {
      case _: (BypassError | ParsingError) => false
      case _ => true
    })


final class MasiroHandler(

  config: MasiroConfig,

  downPath: String,

  defaultUserAgent: String,

  idleIndicator: IdleIndicator,

  cookiesHolder: RequestCookiesHolder,

  taskHelper: TaskHelper,

) extends moe.karla.handler.Handler:

  
  def canHandle(url: String) =
    ZIO.succeed:
      config.chapterPatterns.exists(url.matches(_)) || 
      config.novelViewPatterns.exists(url.matches(_)) ||
      url.matches("masiro://[v|V](\\d+)") ||
      url.matches("masiro://[c|C](\\d+)")


  def isIdle = idleIndicator.isIdle


  private def setIdle(value: Boolean) = idleIndicator.setIdle(value)

    
  extension (request: Request)
    private def preconfigure =
      request
        .removeHeader("User-Agent")
        .addHeader("User-Agent", config.agent.getOrElse(defaultUserAgent))
        .removeHeader("Accecpt-Language")
        .addHeader("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
        .removeHeader("Accept")
        .addHeader("Accept", "*/*")


  private inline def makeURL(uriString: String): IO[MalformedURLException, URL] = 
    ZIO.fromEither(URL.decode(
      uriString match
        case u if u.matches("masiro://[v|V](\\d*)") =>
          s"${config.defaultLocation}/novelView?novel_id=${uriString.replace("masiro://[v|V]", "")}"
        case u if u.matches("masiro://[c|C](\\d*)") =>
          s"${config.defaultLocation}/novelReading?cid=${uriString.replaceAll("masiro://[c|C]", "")}"
        case _ => uriString
    ))
  
  
  private def getPage(url: URL) =
    for
      rc <- cookiesHolder.getCookies
      client <- ZIO.service[Client]
      bodyString <- 
        client.request:
            Request.get(url)
              .preconfigure
              .addHeader("Referer", url.encode)
              .addHeader(Header.Cookie(NonEmptyChunk.fromChunk(rc).get))
          .tap: re =>
            ZIO.unless(re.status.code == 403)(
              ZIO.succeed(re.headers.getAll(SetCookie)).flatMap: sc =>
                cookiesHolder.setCookies:
                  rc.map { cc =>
                    val c = sc.filter(_.value.name == cc.name)
                    if (c.length > 0)
                      cc.content(c.head.value.content)
                    else cc
                  }
            )
          .flatMap: re =>
            if (re.status.code == 403) 
              ZIO.fail(BypassError(s"Cloudflare blocking presents while parsing: '${url}'"))
            else if (re.status.code == 404)
              ZIO.fail(ParsingError(s"404 not found presents while parsing: '${url}'"))
            else if (re.status.code > 500)
              ZIO.fail(ServerError(s"50x presents while parsing: '${url}'")) 
            else re.body.asString
          .tapError: 
            case bp: (BypassError | ParsingError) =>
              ZIO.logError(s"${bp.getMessage}. => abort.")
            case t =>
              ZIO.logError(s"Exception is raised when parsing: '${url}': ${t.getMessage}")
          .defaultRetry
          .tapSomeError:
            case e if !e.isInstanceOf[BypassError | ParsingError] => 
              ZIO.logError(s"Maximum number of retries has been reached while parsing: '${url}'. => abort.")

    yield Jsoup.parse(bodyString).body


  import eu.timepit.refined.auto.autoUnwrap

  private def parseAndRetrivePages(meta: MangaMeta)
    : RIO[zio.http.Client & Scope, (MangaMeta, Chunk[MangaPage])] =
    for
      _ <- ZIO.log(s"Parsing: '${meta.galleryUri}'")

      url <-
        makeURL(meta.galleryUri)
          .tapError: e =>
            ZIO.logError(s"${e.getMessage}. => abort.")

      body <- getPage(url)

      lv <-
        ZIO.attempt:
            Option(body.selectFirst("span.user-lev"))
              .map(_.wholeText.replace("lv", "").strip().toInt)
              .get
          .mapError: _ =>
            ParsingError(s"Extracting user level failed while parsing '${url}'")
          .tapError: e =>
            ZIO.logError:
              s"${e.getMessage}, => abort."

      titleString <-
        ZIO.when(Option(body.selectFirst("img[src='/images/nopay.webp']")).isDefined)(
          for
            balance <-
              ZIO.attempt:
                  Option(body.selectFirst("li.user-header > p > small"))
                    .map(_.wholeText.stripLeading().split(' ')(0).replace("金币:", "").toInt)
                    .get
                .mapError: _ => 
                  ParsingError(s"Extracting balance failed while parsing '${url}'")

            costInt <-
              ZIO.attempt:
                  Option(body.selectFirst("input.cost"))
                    .map(_.attr("value").toInt)
                    .get
                .mapError: _ => 
                  ParsingError(s"Extracting cost failed while parsing '${url}'")

            canPay = 
              config.payment.autoPay && 
                balance > costInt &&
                  balance - costInt >= config.payment.minimumBalance

            _ <- ZIO.unless(canPay)(
              ZIO.fail(InsufficientBalanceError(s"Insufficient balance to pay off: '${url}'."))
            )

            title <-
              ZIO.fromOption:
                  Option(body.selectFirst("div.hint > p > a[href='']"))
                    .map(_.wholeText.filtered)
                    .collect { case s: String if s.size > 0 => s }       
                .mapError: _ =>
                  ParsingError(s"Extracting title failed while parsing '${url}'")

          yield title
        )
        .someOrElseZIO:
          ZIO.fromOption:
              Option(body.selectFirst(".novel-title"))
                .map(_.wholeText.filtered)
                .collect { case s: String if s.size > 0 => s }
            .mapError: _ =>
              ParsingError(s"Extracting title failed while parsing '${url}'")
        .tapError: e =>
          ZIO.logError:
            s"${e.getMessage}, => abort."

      
      parsedPages <- 
        (
          for
            base <-
              ZIO.attempt:
                  s"${url.scheme.get.encode}://${url.host.get}${
                    url.port.map(i => if (i == 443 || i == 80) "" else s":${i}").getOrElse("")
                  }"
                .mapError: _ => 
                  ParsingError(s"Extracting base url failed while parsing '${url}'")
            
            p <-
              ZIO.when(meta.galleryUri.contains("novelView"))(
                ZIO.fromEither(body.getElementsByTag("script").selectFirst("#chapters-json").data.fromJson[List[NovelMeta]])
                  .map:
                    Chunk.fromIterable(_)
                      .filterNot:
                        config.skipChargeableChapter && _.cost > 0
                      .filter:
                        _.limit_lv <= lv
                      .map: jsm =>
                        MangaPage(
                          0,
                          meta.id,
                          s"${base}/admin/novelReading?cid=${jsm.id}",
                          -1,
                          jsm.title,
                          MangaPage.State.Pending.code,
                        )
                      .filter: p =>
                        p.pageUri.length > 1 && p.title.length > 1
              )
              .someOrElse:
                Chunk.single:
                  MangaPage(
                    0,
                    meta.id,
                    url.encode,
                    1,
                    s"${titleString}",
                    MangaPage.State.Pending.code,
                  )
              .mapError: _ => 
                ParsingError(s"Extracting pages failed while parsing '${url}'")

            _ <- 
              ZIO.when(p.length < 1)(
                ZIO.fail:
                  ParsingError(s"No pages found after parsing '${url}'")
              )
          yield p
        )
        .tapError: e =>
          ZIO.logError:
            s"${e.getMessage}, => abort."

      parsedMeta = meta.copy(title = Some(titleString), isParsed = true, totalPages = parsedPages.length)

    yield (parsedMeta, parsedPages)

  end parseAndRetrivePages


  private def downloadSinglePage(title: String, page: MangaPage) = {

    import zio.schema.Schema
    import zio.schema.DeriveSchema
    import zio.schema.codec.JsonCodec.*

    given prespSchema: Schema[PayResponse] = DeriveSchema.gen[PayResponse]

    for
      _ <- ZIO.log(s"Parsing: '${page.pageUri}'")

      pageUrl <- 
        ZIO.fromEither(URL.decode(page.pageUri))
          .tapError: e =>
            ZIO.logError(s"${e.getMessage}. => abort.")

      body <- getPage(pageUrl)


      payResult <-
        ZIO.when(Option(body.selectFirst("img[src='/images/nopay.webp']")).isDefined)(
          for
            _ <- ZIO.logWarning(s"'${pageUrl}' need to pay.")

            balance <-
              ZIO.attempt:
                  Option(body.selectFirst("li.user-header > p > small"))
                    .map(_.wholeText.stripLeading().split(' ')(0).replace("金币:", "").toInt)
                    .get
                .mapError: _ => 
                  ParsingError(s"Extracting balance failed while parsing '${pageUrl}'")

            costInt <-
              ZIO.attempt:
                  Option(body.selectFirst("input.cost"))
                    .map(_.attr("value").toInt)
                    .get
                .mapError: _ => 
                  ParsingError(s"Extracting bill info failed while parsing '${pageUrl}'")

            canPay = 
              config.payment.autoPay && 
                balance > costInt &&
                  balance - costInt >= config.payment.minimumBalance

            _ <- ZIO.unless(canPay)(
              ZIO.logWarning(s"Insufficient balance to pay off: '${pageUrl}'.")
            )
            
            result <- 
              ZIO.when(canPay)(
                for
                  tup <-
                    ZIO.attempt:
                        (
                          Option(body.selectFirst("input.type"))
                            .map(_.attr("value"))
                            .get,
                          Option(body.selectFirst("input.object_id"))
                            .map(_.attr("value"))
                            .get,
                          Option(body.selectFirst("input.csrf"))
                            .map(_.attr("value"))
                            .get,
                        )
                      .mapError: _ =>
                        ParsingError(s"Extracting bill info failed while parsing '${pageUrl}'")

                  (_type, objectId, csrf) = tup


                  base <-
                    ZIO.attempt:
                        s"${pageUrl.scheme.get.encode}://${pageUrl.host.get}${
                          pageUrl.port.map(i => if (i == 443 || i == 80) "" else s":${i}").getOrElse("")
                        }"
                      .mapError: _ => 
                        ParsingError(s"Extracting base url failed while parsing '${pageUrl}'")

                  payUrlString = s"${base}/admin/pay"

                  rc2 <- cookiesHolder.getCookies

                  client <- ZIO.service[Client]

                  resp <-
                    client.request:
                        Request.post(payUrlString, Body.fromURLEncodedForm(
                          Form(
                            FormField.simpleField("cost", costInt.toString()),
                            FormField.simpleField("type", _type),
                            FormField.simpleField("object_id", objectId),
                          )
                        ))
                        .preconfigure
                        .addHeader(Header.Cookie(NonEmptyChunk.fromChunk(rc2).get))
                        .removeHeader("X-CSRF-TOKEN")
                        .addHeader("X-CSRF-TOKEN", csrf)
                      .tap: re =>
                        ZIO.unless(re.status.code == 403)(
                          ZIO.succeed(re.headers.getAll(SetCookie)).flatMap: sc =>
                            cookiesHolder.setCookies:
                              rc2.map { cc =>
                                val c = sc.filter(_.value.name == cc.name)
                                if (c.length > 0)
                                  cc.content(c.head.value.content)
                                else cc
                              }
                        )
                      .flatMap: re =>
                        if (re.status.code == 403) 
                          ZIO.fail(BypassError(s"Cloudflare blocking presents while trying to pay: '${pageUrl}'"))
                        else if (re.status.code == 404)
                          ZIO.fail(ParsingError(s"404 not found presents while trying to pay: '${pageUrl}'"))
                        else if (re.status.code > 500)
                          ZIO.fail(ServerError(s"50x presents while trying to pay: '${pageUrl}'")) 
                        else re.body.to[PayResponse]
                      .tapError: 
                        case bp: (BypassError | ParsingError) =>
                          ZIO.logError(s"${bp.getMessage}. => abort.")
                        case t =>
                          ZIO.logError(s"Exception is raised when trying to pay: '${pageUrl}': ${t.getMessage}")
                      .defaultRetry
                      .tapSomeError:
                        case e if !e.isInstanceOf[BypassError | ParsingError] => 
                          ZIO.logError(s"Maximum number of retries has been reached while trying to pay: '${pageUrl}'. => abort.")
                    
                  _res = resp.code == 1

                  _ <- ZIO.unless(_res)(
                    ZIO.logWarning(s"Unexpected result code of payment: code=${resp.code}.")
                  )
                yield _res
              )

          yield result.getOrElse(false)
        )

      _body: Option[Element] <-
        payResult match 
          case None =>
            ZIO.succeed(Some(body))
          case Some(true) =>
            ZIO.sleep(2 seconds)
              *> getPage(pageUrl).map(Some(_))
          case Some(false) =>
            ZIO.none

      _ <-
        ZIO.when(_body.isDefined)(
          (
            for
              novelContent <-
                ZIO.fromOption:
                    Option(_body.get.selectFirst("div.box-body.nvl-content"))
                      .map(_.wholeText.stripLeading())
                  .mapError: _ => 
                    ParsingError(s"Extracting content failed while parsing '${pageUrl}'")
                  

              _ <- ZIO.attemptBlockingIO(Files.createDirectories(Paths.get(s"${downPath}/${title}")))


              savedPath = s"${downPath}/${title}/${page.title}.txt"
                
              _ <- 
                ZStream(page.title, "\r\n\r\n\r\n\r\n", novelContent)
                  .run:
                    ZSink
                      .fromPath:
                        Paths.get(savedPath)
                      .contramapChunks[String](_.flatMap(_.getBytes))

            yield savedPath
          )
          .tapError: e =>
            ZIO.logError(s"${e.getMessage}. => abort.")
          .flatMap: savedPath =>
            ZIO.log(s"Saved: '${pageUrl}' as '${savedPath}'")
        )

    yield payResult

  }



  def handle(meta: MangaMeta): URIO[zio.http.Client & Scope, Unit] =
    idleIndicator.setIdle(false)
    <* ZIO.whenCaseDiscard(meta.isParsed) {
          case true =>
            Ref.make(meta.completedPages).flatMap: cp =>
                ZStream
                  .fromIterable(meta.completedPages to meta.totalPages)
                  .runForeach(_ =>
                    taskHelper.acquirePage(meta.id).flatMap:
                      case Some(p) =>
                        ZIO.log("Masiro handler is cooling down...") *>
                        ZIO.sleep(config.cooldown) *>
                          downloadSinglePage(meta.title.get, p)
                            .tapError: e =>
                              taskHelper.failPage(p.id)
                            .flatMap:
                              case Some(false) =>
                                taskHelper.failPage(p.id)
                              case _ =>
                                cp.incrementAndGet *> 
                                  taskHelper.completePage(meta.id, p.id)
                      case None =>
                        cp.get.flatMap:
                          case meta.totalPages =>
                            taskHelper.completeManga(meta.id)
                              *> ZIO.log(s"Completed: '${meta.galleryUri}' as '${meta.title.get}'")
                          case _ =>
                            if (meta.totalPages == 1)
                              makeURL(meta.galleryUri).flatMap: gu =>
                                taskHelper.failManga(meta.id, s"Insufficient balance to pay off: '${gu}'.")
                            else
                              ZIO.ifZIO(taskHelper.partiallyCompleteManga(meta.id))(
                                onTrue =
                                  ZIO.logWarning(s"Partially completed: '${meta.galleryUri}' as '${meta.title.get}'")
                                ,
                                onFalse = ZIO.fail(LostMetaError(s"Meta (id = ${meta.id}) may be removed"))
                              )
                  )
              *> idleIndicator.setIdle(true)

          case false =>
            for
              t <- parseAndRetrivePages(meta)
              (m, ps) = t
              _ <- taskHelper.submitParsed(m, ps.toList)
              _ <- ZIO.log(s"Parsed: '${m.galleryUri}' as '${m.title.get}'")
              _ <- handle(m)
            yield ()
        }
        .tapError: 
          case l: LostMetaError =>
            ZIO.logError(s"MasiroHandler: ${l.message}. => abort.")
          case e =>
            taskHelper.failManga(meta.id, e.getMessage())
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
          ZIO.logError(s"MasiroHandler: ${cause.dieOption.map(_.getMessage()).getOrElse("???")}. => die.")
          

  end handle