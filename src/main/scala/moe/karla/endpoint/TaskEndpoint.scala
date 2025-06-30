package moe.karla.endpoint

import moe.karla.config.AppConfig
import moe.karla.repo.*
import moe.karla.metadata.*


import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.json.*

import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill

import scala.io.Source




object TaskEndpoint:


  case class PrettyMangaMeta(
    id: Int,
    galleryUri: String,
    title: Option[String],
    progress: String,
  )

  given encoder: JsonEncoder[PrettyMangaMeta] = DeriveJsonEncoder.gen[PrettyMangaMeta]

  //given mangaMetaSchema: Schema[MangaMeta] = DeriveSchema.gen[MangaMeta]

  //given prettyMangaMetaSchema: Schema[PrettyMangaMeta] = DeriveSchema.gen[PrettyMangaMeta]


  extension (m: MangaMeta)
    def toPretty = {

      import MangaMeta.State.*

      PrettyMangaMeta(
        m.id, m.galleryUri, m.title, m.state match
          case Pending.code => "Pending"
          case WaitingForHandler.code => "Waiting for handler"
          case Running.code => s"${m.completedPages} / ${m.totalPages}"
          case Completed.code => "Completed"
          case Failed.code => "Failed"

          case _ => throw IllegalArgumentException("Undefined code") 
      )
    }

  private def transaction[R, A](op: ZIO[R, Throwable, A]): ZIO[R & Quill.Sqlite[SnakeCase], Throwable, A] = 
    ZIO.serviceWithZIO[Quill.Sqlite[SnakeCase]](_.transaction(op))
  

  def routes = Routes(
    Method.GET / "tasks" -> Handler.fromFunctionZIO { (req: Request) =>
      for
        metaRepo <- ZIO.service[MangaMetaRepo]
        tasks <- transaction(metaRepo.all)
      yield 
        if (req.queryParam("pretty").isDefined) Response.json(tasks.map(_.toPretty).toJsonPretty)
        else Response.json(tasks.toJsonPretty)
    },

    Method.POST / "tasks" -> Handler.fromFunctionZIO { (req: Request) =>
      for
        content <- req.body.asString
        metaRepo <- ZIO.service[MangaMetaRepo]
        lines <-
          ZIO.attempt(
            Source.fromString(content).getLines().map(_.strip()).filterNot(_ == "").filter(URL.decode(_).isRight).toList
          )
        _ <- 
          ZIO.when(lines.size > 0)(
            transaction(metaRepo.batchCreate(lines.map(MangaMeta(0, _, false, None, 0, 0, MangaMeta.State.Pending.code, None, None))))
          )

      yield if (lines.size > 0) Response.json(lines.toJsonPretty) else Response.badRequest
    },
    
    Method.DELETE / "task" / int("id") -> handler { (id: MetaId, _: Request) =>
      for
        metaRepo <- ZIO.service[MangaMetaRepo]
        opt <- metaRepo.getById(id)
        resp <- opt match
          case None => ZIO.succeed(Response.notFound)
          case Some(meta) => transaction(
            metaRepo.deleteById(meta.id) *> ZIO.serviceWithZIO[MangaPageRepo](_.deleteByMetaId(meta.id))
          ) *> ZIO.succeed(Response.ok)
      yield resp
    },

    Method.POST / "task" / int("id") / "reset" -> handler { (id: MetaId, _: Request) =>
      for
        metaRepo <- ZIO.service[MangaMetaRepo]
        pageRepo <- ZIO.service[MangaPageRepo]
        opt <- metaRepo.getById(id)
        resp <- opt match
          case None => ZIO.succeed(Response.notFound)
          case Some(meta) => transaction(
            metaRepo.setStateById(MangaMeta.State.Pending)(meta.id) *>
              pageRepo.setStateByMetaIdAndStateIn(MangaPage.State.Pending)(meta.id, MangaPage.State.Failed, MangaPage.State.Running)
          ) *> ZIO.succeed(Response.ok)
      yield resp
    }
  )
  .handleError:
    Response.fromThrowable(_)
