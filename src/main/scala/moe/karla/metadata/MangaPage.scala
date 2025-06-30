package moe.karla.metadata




type PageId = Int

final case class MangaPage(
  id: PageId,
  metaId: MetaId,
  pageUri: String,
  pageNumber: Int,
  title: String,
  state: Short,
)

object MangaPage:

  enum State(val code: Short):
    case Pending extends State(0)
    case Running extends State(1)
    case Completed extends State(2)
    case Interrupted extends State(-1)
    case Failed extends State(-2)
      
