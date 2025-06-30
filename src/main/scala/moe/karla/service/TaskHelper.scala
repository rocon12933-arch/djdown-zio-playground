package moe.karla.service



import moe.karla.metadata.*
import moe.karla.metadata.MangaMeta.*
import moe.karla.metadata.MangaPage.*
import moe.karla.repo.*
import moe.karla.repo.MangaMetaRepo.*
import moe.karla.repo.MangaPageRepo.*


import zio.*
import zio.stream.*

import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill

import scala.concurrent.duration.DurationInt



final class TaskHelper(

  quill: Quill.Sqlite[SnakeCase],

  metaRepo: MangaMetaRepo,

  pageRepo: MangaPageRepo,
):
  
  import quill.*


  private def _acquireMeta(states: MangaMeta.State*)(newState: MangaMeta.State) = 
    transaction(
      for
        opt <- metaRepo.getFirstByStateInOption(states*)
        _ <- opt match
          case Some(meta) => metaRepo.setStateById(newState)(meta.id)
          case None => ZIO.unit
      yield opt
    )

  
  def acquireMetaToWaiting = _acquireMeta(MangaMeta.State.Pending)(MangaMeta.State.WaitingForHandler)


  def pushWaitingMetaToRunning(metaId: MetaId) = metaRepo.setStateById(MangaMeta.State.Running)(metaId)


  def getAllWaitingMeta = metaRepo.getByState(MangaMeta.State.WaitingForHandler)


  private def _acquirePage(metaId: MetaId, state: MangaPage.State)(newState: MangaPage.State) = 
    transaction(
      for
        opt <- pageRepo.getFirstByMetaIdAndState(metaId, state)
        _ <- opt match
          case Some(page) => pageRepo.setStateById(newState)(page.id)
          case None => ZIO.unit
      yield opt
    )


  def acquirePage(metaId: MetaId) = _acquirePage(metaId, MangaPage.State.Pending)(MangaPage.State.Running)


  def checkMetaExists(metaId: MetaId) =
    transaction(metaRepo.getById(metaId)).map(_.isDefined)
    

  def submitParsed(mangaMeta: MangaMeta, pages: List[MangaPage]) =
    transaction(
      pageRepo.batchCreate(pages) *>
        metaRepo.updateFastly(mangaMeta.copy(state = MangaMeta.State.Running.code, isParsed = true)) 
    )


  private def submitParsedMeta(mangaMeta: MangaMeta) =
    transaction(metaRepo.updateFastly(mangaMeta.copy(state = MangaMeta.State.Running.code, isParsed = true)))


  private def submitParsedPages(pages: List[MangaPage]) =
    transaction(pageRepo.batchCreate(pages))


  def completePage(pageId: PageId) =
    transaction(
      for
        pre <- pageRepo.setStateById(MangaPage.State.Failed)(pageId)
        meta <- metaRepo.getByPageId(pageId)
        mre <-
          meta match 
            case Some(m) =>
              metaRepo.increaseCompletedPages(m.id)
            case None =>
              ZIO.succeed(false)
      yield pre && mre
    )
    

  def completePage(metaId: MetaId, pageId: PageId) =
    /*
    transaction(
      pageRepo.updateStateById(MangaPage.State.Completed)(pageId) &>
        metaRepo.increaseCompletedPages(metaId)
    )
    */
    transaction(
      pageRepo.deleteById(pageId) *>
        metaRepo.increaseCompletedPages(metaId)
    )


  def failPageWithExtra(pageId: PageId, extra: String) =
    transaction(
      for
        meta <- metaRepo.getByPageId(pageId)
        re <-
          meta match 
            case Some(m) =>
              pageRepo.setStateById(MangaPage.State.Failed)(pageId) *>
                metaRepo.setExtraById(Some(extra))(m.id)
            case None =>
              ZIO.succeed(false)
      yield re
    )

  
  def failPageWithExtra(metaId: MetaId, pageId: PageId, extra: String) =
    transaction(
      pageRepo.setStateById(MangaPage.State.Failed)(pageId)
        *> metaRepo.setExtraById(Some(extra))(metaId)
    )


  def interrptPage(pageId: PageId) =
    transaction(pageRepo.setStateById(MangaPage.State.Interrupted)(pageId))


  def failPage(pageId: PageId) =
    transaction(pageRepo.setStateById(MangaPage.State.Failed)(pageId))


  def partiallyCompleteManga(metaId: MetaId) =
    transaction(metaRepo.setStateById(MangaMeta.State.PartiallyCompleted)(metaId))

  
  def completeManga(metaId: MetaId) =
    transaction(metaRepo.setStateById(MangaMeta.State.Completed)(metaId))


  def failManga(metaId: MetaId, cause: String) =
    transaction(metaRepo.setStateAndCauseById(MangaMeta.State.Failed, Some(cause))(metaId))

  
  def updateExtraField(metaId: MetaId, extra: Option[String]) =
    transaction(metaRepo.setExtraById(extra)(metaId))


object TaskHelper:
  val layer = 
    ZLayer {
      for
        quill <- ZIO.service[Quill.Sqlite[SnakeCase]]
        metaRepo <- ZIO.service[MangaMetaRepo]
        pageRepo <- ZIO.service[MangaPageRepo]
      yield TaskHelper(quill, metaRepo, pageRepo)
    }