package moe.karla.service


import moe.karla.config.AppConfig
import moe.karla.repo.*
import moe.karla.metadata.*
import moe.karla.handler.Handler
import moe.karla.handler.Handlers

import zio.*
import zio.stream.*

import eu.timepit.refined.auto.autoUnwrap

import io.getquill.SnakeCase


import scala.concurrent.duration.DurationInt


/*
extension [R, E, A, B] (v: => ZIO[R, E, Option[A]])

  private def continueOption(f: A => ZIO[R, E, B])(using Trace): ZIO[R, E, Option[B]] =
    ZIO.suspendSucceed:
      for 
        opt <- v
        b <- opt match
          case None => ZIO.none
          case Some(value) => f(value).asSome
      yield b
*/

final class DownloadHub(

  appConfig: AppConfig,

  taskHelper: TaskHelper,

  handlers: Chunk[Handler]
):
  
  private def foldHandlers(meta: MangaMeta) =
    ZIO.foldLeft(handlers)((false, false)) { (prev, handler) =>

      if (prev._2) ZIO.succeed(prev)
      else
        for
          canHandle <- handler.canHandle(meta.galleryUri)
          isIdle <- handler.isIdle
          _ <-
            if (canHandle && isIdle) {
              handler.handle(meta).fork
            }
            else ZIO.unit
        yield (prev._1 || canHandle, canHandle && isIdle)
    }


  def run = 
    ZStream.repeatWithSchedule(0, Schedule.spaced(4 seconds)).foreach: _ =>
      for
        waitingManga <- taskHelper.getAllWaitingMeta
        
        _ <- ZIO.foreach(waitingManga)(meta =>
          foldHandlers(meta).flatMap(r => 
            r match 
              case (_, true) =>
                taskHelper.pushWaitingMetaToRunning(meta.id) <* ZIO.sleep(2 seconds)
              case (true, _) => ZIO.unit
              case (false, false) => 
                taskHelper.failManga(meta.id, "No compatible handler")
                  <* ZIO.logError(s"'${meta.galleryUri}': No compatible handler.")
          )
        )
        _ <-
          if (waitingManga.length >= appConfig.maxWaiting) 
            ZIO.none
          else taskHelper.acquireMetaToWaiting
      yield ()

end DownloadHub


object DownloadHub:
  
  import Handlers.defaultHandlers

  val layer =
    ZLayer.derive[Handlers] >>> ZLayer {
      for
        appConfig <- ZIO.service[AppConfig]

        taskHelper <- ZIO.service[TaskHelper]

        handlers <- ZIO.service[Handlers]

      yield DownloadHub(appConfig, taskHelper, handlers.list)
    }

    
  def runDaemon = ZIO.serviceWithZIO[DownloadHub](_.run).fork