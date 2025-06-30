package moe.karla.repo


import moe.karla.metadata.*

import zio.*

import io.getquill.*
import io.getquill.jdbczio.Quill



class MangaPageRepo(quill: Quill.Sqlite[SnakeCase]):

  import quill.*
  import MangaPage.State
  import MangaPage.State.*


  private inline def pageQuery = querySchema[MangaPage]("manga_page")
  

  private inline def queryGetById(id: PageId) = quote {
    pageQuery.filter(_.id == lift(id)).take(1)
  }


  private inline def queryUpdateState(state: State) = quote {
    pageQuery.update(_.state -> lift(state.code))
  }


  private inline def queryUpdateStateById(newState: State)(id: PageId) = quote {
    pageQuery.filter(_.id == lift(id)).update(_.state -> lift(newState.code))
  }


  private inline def queryUpdateStateByStateIn(newState: State)(state: Seq[State]) =
    quote {
      pageQuery
        .filter(p => liftQuery(state.map(s => s.code)).contains(p.state))
        .update(_.state -> lift(newState.code))
    }


  private inline def queryUpdateStateByMetaIdAndStateIn(newState: State)(metaId: MetaId, in: Seq[State]) =
    quote {
      pageQuery
        .filter(_.metaId == lift(metaId))
        .filter(p => liftQuery(in.map(s => s.code)).contains(p.state))
        .update(_.state -> lift(newState.code))
    }


  private inline def queryUpdateStateByMetaIdAndStateExcept(newState: State)(metaId: MetaId, excepts: Seq[State]) = 
    quote {
      pageQuery
        .filter(_.metaId == lift(metaId))
        .filter(p => !liftQuery(excepts.map(s => s.code)).contains(p.state))
        .update(_.state -> lift(newState.code))
    }


  private inline def queryGetFirstByState(state: State) = quote {
    pageQuery.filter(_.state == lift(state.code)).take(1)
  }


  private inline def queryGetFirstByMetaIdAndState(metaId: MetaId, state: State) = quote {
    pageQuery.filter(_.metaId == lift(metaId)).filter(_.state == lift(state.code)).take(1)
  }


  private inline def queryGetFirstByMetaIdAndStateIn(metaId: MetaId, states: State*) = quote {
    pageQuery
      .filter(_.metaId == lift(metaId))
      .filter(p => liftQuery(states.map(s => s.code)).contains(p.state))
      .take(1)
  }


  private inline def queryDeleteById(id: Int) = quote {
    pageQuery.filter(_.id == lift(id)).delete
  }


  private inline def queryDeleteByMetaId(metaId: MetaId) = quote {
    pageQuery.filter(_.metaId == lift(metaId)).delete
  }


  private inline def queryInsert(metaId: MetaId, pageUri: String, pageNumber: Int, title: String, state: State) =
    quote { 
      pageQuery.insert(
        _.metaId -> lift(metaId), 
        _.pageNumber -> lift(pageNumber), 
        _.pageUri -> lift(pageUri), 
        _.title -> lift(title), 
        _.state -> lift(state.code)
      )
    }
    

  private inline def batchInsert(li: List[MangaPage]) = 
    quote { 
      liftQuery(li).foreach(p =>
        pageQuery.insert(
          _.metaId -> p.metaId, 
          _.pageNumber -> p.pageNumber,
          _.pageUri -> p.pageUri, 
          _.title -> p.title, 
          _.state -> p.state,
        )
      )
    }
  

  
  def underlying = quill
  

  def getById(id: PageId) = run(queryGetById(id)).map(_.headOption)

  
  def create(metaId: MetaId, pageUri: String, pageNumber: Int, path: String, state: State) = 
    run(queryInsert(metaId, pageUri, pageNumber, path, state))
  
  
  def setAllState(state: State) = run(queryUpdateState(state))


  def setStateById(newState: State)(id: PageId) = run(queryUpdateStateById(newState)(id)).map(_ > 0)


  def setStateByStateIn(newState: State)(in: State*) = run(queryUpdateStateByStateIn(newState)(in))


  def setStateByMetaIdAndStateIn(newState: State)(metaId: MetaId, in: State*) = 
    run(queryUpdateStateByMetaIdAndStateIn(newState)(metaId, in))


  def setStateByMetaIdAndStateExcept(newState: State)(metaId: MetaId, excepts: State*) = 
    run(queryUpdateStateByMetaIdAndStateExcept(newState)(metaId, excepts))


  def batchCreate(li: List[MangaPage]) = 
    run(batchInsert(li))

  
  def getFirstByState(state: State) =
    run(queryGetFirstByState(state)).map(_.headOption)

  
  def getFirstByMetaIdAndState(metaId: MetaId, state: State) =
    run(queryGetFirstByMetaIdAndState(metaId, state)).map(_.headOption)


  def deleteById(id: Int) = run(queryDeleteById(id)).map(_ > 0)


  def deleteByMetaId(metaId: MetaId) = run(queryDeleteByMetaId(metaId))

  
    
object MangaPageRepo:
  
  val layer = ZLayer.fromFunction(MangaPageRepo(_))

  
  

