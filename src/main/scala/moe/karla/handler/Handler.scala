package moe.karla.handler



import moe.karla.config.AppConfig
import moe.karla.service.TaskHelper
import moe.karla.metadata.MangaMeta
import moe.karla.handler.default.NHentaiHandler
import moe.karla.handler.default.MasiroHandler
import moe.karla.handler.default.HentaiMangaHandler
import moe.karla.handler.default.KemonoHandler

import zio.*
import zio.ZLayer.Derive.Default

import zio.http.*
import zio.http.Client



trait Handler:

  def handle(meta: MangaMeta): URIO[zio.http.Client & Scope, Unit]

  def canHandle(url: String): UIO[Boolean]

  def isIdle: UIO[Boolean]



class Handlers(val list: Chunk[Handler])



object Handlers:

  given defaultHandlers: Default.WithContext[AppConfig & TaskHelper, Nothing, Chunk[moe.karla.handler.Handler]] =
    Default.fromLayer:
      default.CommonConfig.layer >>> ZLayer {
        for 
          nhentai <- ZIO.service[NHentaiHandler].provideLayer(NHentaiHandler.layer)

          hentaiManga <- ZIO.service[HentaiMangaHandler].provideLayer(HentaiMangaHandler.layer)

          masiro <- ZIO.service[MasiroHandler].provideLayer(MasiroHandler.layer)

          kemono <- ZIO.service[KemonoHandler].provideLayer(KemonoHandler.layer)

        yield Chunk(nhentai, hentaiManga, masiro, kemono)
      }

