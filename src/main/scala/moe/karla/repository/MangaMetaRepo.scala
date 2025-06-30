package moe.karla.repo


import moe.karla.metadata.*


import zio.*
import zio.json.*

import io.getquill.*
import io.getquill.jdbczio.Quill

import java.sql.SQLException
import java.sql.Timestamp




class MangaMetaRepo(quill: Quill.Sqlite[SnakeCase]):

  import quill.*
  import MangaMeta.State


  private inline def metaQuery = querySchema[MangaMeta]("manga_meta")


  private inline def queryGetById(id: MetaId) = quote {
    metaQuery.filter(_.id == lift(id)).take(1)
  }


  private inline def queryGetFirstByState(state: State) = quote {
    metaQuery.filter(_.state == lift(state.code)).take(1)
  }


  private inline def queryGetByState(state: State) = quote {
    metaQuery.filter(_.state == lift(state.code))
  }


  private inline def queryGetFirstByIsParsedAndState(isParsed: Boolean, state: State) = quote {
    metaQuery.filter(_.state == lift(state.code)).filter(_.isParsed == lift(isParsed)).take(1)
  }


  private inline def queryGetByStateIn(states: Seq[State]) = quote {
    metaQuery.filter(m => liftQuery(states.map(_.code)).contains(m.state))
  }

  
  private inline def queryGetFirstByStateIn(states: Seq[State]) = quote {
    metaQuery.filter(m => liftQuery(states.map(_.code)).contains(m.state)).take(1)
  }

    
  private inline def queryGetByPageId(pageId: Int) =
    quote {
      for
        m <- metaQuery.take(1)
        p <- querySchema[MangaPage]("manga_page").join(p => p.metaId == m.id).filter(_.id == lift(pageId))
      yield m
    }


  private inline def queryGetFirstByIsParsedAndStateIn(isParsed: Boolean, states: Seq[State]) = quote {
    metaQuery.filter(m => liftQuery(states.map(_.code)).contains(m.state)).filter(_.isParsed == lift(isParsed)).take(1)
  }


  private inline def queryUpdateStateById(newState: State)(id: MetaId) = quote {
    metaQuery.filter(_.id == lift(id)).update(_.state -> lift(newState.code))
  }


  private inline def queryUpdateStateByStateIn(newState: State)(states: Seq[State]) = quote {
    metaQuery.filter(m => liftQuery(states.map(_.code)).contains(m.state)).update(_.state -> lift(newState.code))
  }


  private inline def queryUpdateStateByIsParsedAndStateIn(newState: State)(isParsed: Boolean, states: Seq[State]) = 
    quote {
      metaQuery
        .filter(_.isParsed == lift(isParsed))
        .filter(m => liftQuery(states.map(_.code)).contains(m.state))
        .update(_.state -> lift(newState.code))
    }


  private inline def queryIncreaseCompletedPages(id: MetaId) = quote {
    metaQuery.filter(_.id == lift(id)).update(r => r.completedPages -> (r.completedPages + 1))
  }


  private inline def queryDeleteById(id: MetaId) = quote {
    metaQuery.filter(_.id == lift(id)).delete
  }


  private inline def queryUpdateStateAndCauseById(newState: State, cause: Option[String])(id: MetaId) = quote {
    metaQuery.filter(_.id == lift(id)).update(
      _.state -> lift(newState.code), _.cause -> lift(cause)
    )
  }


  private inline def queryUpdateExtraById(extra: Option[String])(id: MetaId) = quote {
    metaQuery.filter(_.id == lift(id)).update(
      _.extra -> lift(extra)
    )
  }


  private inline def queryInsert(
    galleryUri: String, 
    isParsed: Boolean, 
    title: Option[String], 
    totalPages: Int, 
    state: Short
  ) =
    quote { 
      metaQuery.insert(
        _.galleryUri -> lift(galleryUri),
        _.isParsed -> lift(isParsed),
        _.title -> lift(title),
        _.totalPages -> lift(totalPages),
        _.state -> lift(state),
        _.completedPages -> lift(0),
      )
      .returning(_.id)
    }


  private inline def batchInsert(li: List[MangaMeta]) =
    quote { 
      liftQuery(li).foreach(p =>
        metaQuery.insert(
          _.galleryUri -> p.galleryUri, 
          _.isParsed -> p.isParsed,
          _.totalPages -> p.totalPages,
          _.title -> p.title, 
          _.completedPages -> 0, 
          _.state -> p.state,
        )
        .returning(_.id)
      )
    }


  def getById(id: MetaId) = run(quote { queryGetById(id) }).map(_.headOption)


  def all = run(quote { metaQuery })


  def getByPageId(pageId: PageId) =
    run(queryGetByPageId(pageId)).map(_.headOption)


  /*
  def updateFastly(meta: MangaMeta) = 
    run(quote { metaQuery.filter(_.id == lift(meta.id)).updateValue(lift(meta)) }).map(_ > 0)
  */
  
  def updateFastly(meta: MangaMeta) = 
    run(quote { metaQuery.filter(_.id == lift(meta.id)).update(
      _.galleryUri -> lift(meta.galleryUri),
      _.totalPages -> lift(meta.totalPages),
      _.isParsed -> lift(meta.isParsed),
      _.title -> lift(meta.title), 
      _.completedPages -> lift(meta.completedPages), 
      _.state -> lift(meta.state),
      _.cause -> lift(meta.cause),
      _.extra -> lift(meta.extra),
    ) }).map(_ > 0)


  def setStateById(newState: State)(id: MetaId) = run(queryUpdateStateById(newState)(id)).map(_ > 0)

  
  def setStateAndCauseById(newState: State, cause: Option[String])(id: MetaId) = 
    run(queryUpdateStateAndCauseById(newState, cause)(id: MetaId)).map(_ > 0)

  
  def setExtraById(extra: Option[String])(id: MetaId) = 
    run(queryUpdateExtraById(extra)(id: MetaId)).map(_ > 0)


  def increaseCompletedPages(id: MetaId) = run(queryIncreaseCompletedPages(id)).map(_ > 0)


  def deleteById(id: MetaId) = run(queryDeleteById(id)).map(_ > 0)


  def getFirstByStateOption(state: State) = 
    run(queryGetFirstByState(state)).map(_.headOption)


  def getByState(state: State) = run(queryGetByState(state))
    

  def getFirstByStateInOption(state: State*) = 
    run(queryGetFirstByStateIn(state)).map(_.headOption)

  
  def getByStateIn(state: State*) = 
    run(queryGetFirstByStateIn(state))


  def getFirstByStateInOption(isParsed: Boolean, state: State*) = 
    run(queryGetFirstByIsParsedAndStateIn(isParsed, state)).map(_.headOption)

  
  def setStateByStateIn(newState: State)(states: State*) = run(queryUpdateStateByStateIn(newState)(states))


  def setStateByIsParsedAndStateIn(newState: State)(isParsed: Boolean, states: State*) = 
    run(queryUpdateStateByIsParsedAndStateIn(newState)(isParsed, states))


  def batchCreate(li: List[MangaMeta]) = run(batchInsert(li))


object MangaMetaRepo:
  
  val layer = ZLayer.fromFunction(new MangaMetaRepo(_))