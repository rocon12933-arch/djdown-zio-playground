package moe.karla.metadata



import zio.json.*



type MetaId = Int


final case class MangaMeta(
  id: MetaId,
  galleryUri: String,
  isParsed: Boolean,
  title: Option[String],
  totalPages: Int,
  completedPages: Int,
  state: Short,
  cause: Option[String],
  extra: Option[String]
)

object MangaMeta:

  given encoder: JsonEncoder[MangaMeta] = DeriveJsonEncoder.gen[MangaMeta]

  
  enum State(val code: Short):
    case Failed extends State(-2)
    case Interrupted extends State(-1)
    case Pending extends State(0)
    case WaitingForHandler extends State(1)
    case Running extends State(2)
    case PartiallyCompleted extends State(3)
    case Completed extends State(4)
    

    def fromCode(code: Short) = 
      code match 
        case Pending.code => Pending
        case WaitingForHandler.code => WaitingForHandler
        case Running.code => Running
        case Completed.code => Completed
        case PartiallyCompleted.code => PartiallyCompleted
        case Interrupted.code => Interrupted
        case Failed.code => Failed
        case _ => throw IllegalArgumentException("No such code in predefined states") 


