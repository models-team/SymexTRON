package syntax.ast

import monocle.{POptional, Lens, PLens, Iso}
import monocle.macros.{GenIso, GenLens, GenPrism}
import monocle.std.tuple2._
import monocle.function.Field2._
import language.higherKinds
import scalaz._, Scalaz._
import helper.Counter

case class Class(name: String) // To be defined later

sealed trait Cardinality { def isOptional: Boolean }
case class Single() extends Cardinality {
  def isOptional = false
}
case class Many() extends Cardinality {
  def isOptional = true
}
case class Opt() extends Cardinality {
  def isOptional = true
}

case class ClassDefinition(name: String, children: Map[Fields, (Class, Cardinality)],
                           refs: Map[Fields, (Class, Cardinality)], supers: Class*)

sealed trait BasicExpr
case class Symbol(id: Symbols) extends BasicExpr
case class Var(name: Vars) extends BasicExpr

sealed trait SetExpr
case class SetLit(es: BasicExpr*) extends SetExpr
case class Union(e1 : SetExpr, e2 : SetExpr) extends SetExpr
case class Diff(e1 : SetExpr, e2 : SetExpr) extends SetExpr
case class ISect(e1 : SetExpr, e2 : SetExpr) extends SetExpr
case class SetVar(name: Vars) extends SetExpr
case class SetSymbol(id: Symbols) extends SetExpr

sealed trait BoolExpr
case class Eq(e1: SetExpr, e2: SetExpr) extends BoolExpr
case class ClassMem(e1: SetExpr, s: Class) extends BoolExpr
case class SetMem(e1: BasicExpr, e2: SetExpr) extends BoolExpr
case class SetSub(e1: SetExpr, e2: SetExpr) extends BoolExpr
case class SetSubEq(e1: SetExpr, e2: SetExpr) extends BoolExpr
case class And(bs: BoolExpr*) extends BoolExpr
case class Not(b: BoolExpr) extends BoolExpr

sealed trait MatchExpr
case class MSet(e : SetExpr) extends MatchExpr
case class Match(e : SetExpr, c : Class) extends MatchExpr
case class MatchStar(e : SetExpr, c : Class) extends MatchExpr

object MatchExpr {
  val _me_e = Lens[MatchExpr, SetExpr]({
      case MSet(e) => e
      case Match(e, c) => e
      case MatchStar(e, c) => e
    })(newe => {
      case MSet(e) => MSet(newe)
      case Match(e, c) => Match(newe, c)
      case MatchStar(e, c) => MatchStar(newe, c)
    })
}

sealed abstract class SpatialDesc
case class AbstractDesc(c : Class, unowned : SetExpr) extends SpatialDesc
case class ConcreteDesc(c : Class, children : Map[Fields, SetExpr], refs : Map[Fields, SetExpr]) extends SpatialDesc

object SpatialDesc {
  val _sd_abstract = GenPrism[SpatialDesc, AbstractDesc]
  val _sd_concrete = GenPrism[SpatialDesc, ConcreteDesc]
  val _sd_c = Lens[SpatialDesc, Class]({ case ConcreteDesc(c, _, _) => c case AbstractDesc(c, _) => c })(newc => {
    case ConcreteDesc(oldc, chld, refs) => ConcreteDesc(newc, chld, refs)
    case AbstractDesc(oldc, unowned) => AbstractDesc(newc, unowned)
  })
}

object AbstractDesc {
  val _ad_c = GenLens[AbstractDesc](_.c)
  val _ad_unowned = GenLens[AbstractDesc](_.unowned)
}

object ConcreteDesc {
  val _cd_c = GenLens[ConcreteDesc](_.c)
  val _cd_children = GenLens[ConcreteDesc](_.children)
  val _cd_refs = GenLens[ConcreteDesc](_.refs)
}

case class QSpatial(e : SetExpr, c : Class, unowned : SetExpr)

object QSpatial {
  val _qs_e = GenLens[QSpatial](_.e)
  val _qs_c = GenLens[QSpatial](_.c)
  val _qs_unowned = GenLens[QSpatial](_.unowned)
}

case class SHeap(spatial: Spatial[Symbols], qspatial: Set[QSpatial], pure : Prop)

object SHeap {
  val _sh_spatial  = GenLens[SHeap](_.spatial)
  val _sh_qspatial = GenLens[SHeap](_.qspatial)
  val _sh_pure     = GenLens[SHeap](_.pure)
}

case class SMem(stack: SStack, heap: SHeap)

object SMem {
  val _sm_stack = GenLens[SMem](_.stack)
  val _sm_heap = GenLens[SMem](_.heap)
}

case class CHeap(typeenv: Map[Instances, Class],
                 childenv: Map[Instances, Map[Fields, Set[Instances]]],
                 refenv: Map[Instances, Map[Fields, Set[Instances]]])

object CHeap {
  val _ch_typeenv  = GenLens[CHeap](_.typeenv)
  val _ch_childenv = GenLens[CHeap](_.childenv)
  val _ch_refenv   = GenLens[CHeap](_.refenv)
}

case class CMem(stack: CStack, heap: CHeap)

object CMem {
  val _cm_stack = GenLens[CMem](_.stack)
  val _cm_heap  = GenLens[CMem](_.heap)
}

sealed trait Statement
case class StmtSeq(metaInf: Statement.MetaInf, ss : Statement*)
  extends Statement
case class AssignVar(metaInf: Statement.MetaInf, x : Vars, e : SetExpr)
  extends Statement
case class LoadField(metaInf: Statement.MetaInf, x : Vars, e : SetExpr, f : Fields)
  extends Statement
case class New(metaInf: Statement.MetaInf, x : Vars, c : Class)
  extends Statement
case class AssignField(metaInf: Statement.MetaInf, e1 : SetExpr, f : Fields, e2 : SetExpr)
  extends Statement
case class If(metaInf: Statement.MetaInf, cs : (BoolExpr, Statement)*)
  extends Statement
case class For(metaInf: Statement.MetaInf, x: Vars, m: MatchExpr, sb: Statement)
  extends Statement
case class Fix(metaInf: Statement.MetaInf, e : SetExpr, sb: Statement)
  extends Statement

object Statement {
  sealed trait MetaInf
  case class MI(uid: Integer) extends MetaInf
  case class NoMI() extends MetaInf

  val _stmt_metaInf = Lens[Statement, MetaInf]({
        case StmtSeq(minf, _*) => minf
        case AssignVar(minf, _, _) => minf
        case LoadField(minf, _, _, _) => minf
        case New(minf, _, _) => minf
        case AssignField(minf, _, _, _) => minf
        case If(minf, _*) => minf
        case For(minf, _, _, _) => minf
        case Fix(minf, _, _) => minf
  })(nminf => {
        case StmtSeq(_, ss@_*) => StmtSeq(nminf, ss:_*) // copy doesn't work on list arguments apparently
        case s: AssignVar => s.copy(metaInf = nminf)
        case s: LoadField => s.copy(metaInf = nminf)
        case s: New => s.copy(metaInf = nminf)
        case s: AssignField => s.copy(metaInf = nminf)
        case If(_, cs@_*) => If(nminf, cs:_*) // copy doesn't work on list arguments apparently
        case s: For => s.copy(metaInf = nminf)
        case s: Fix => s.copy(metaInf = nminf)
    })

  private val _stmt_mi = _stmt_metaInf composePrism GenPrism[MetaInf, MI]
  val _stmt_uid = _stmt_mi composeLens GenLens[MI](_.uid)

  def stmtSeq(ss : Statement*) : Statement = StmtSeq(NoMI(), ss :_*)
  def assignVar(x : Vars, e : SetExpr) : Statement = AssignVar(NoMI(), x, e)
  def loadField(x : Vars, e : SetExpr, f : Fields) : Statement = LoadField(NoMI(), x, e, f)
  def `new`(x : Vars, c : Class) : Statement = New(NoMI(), x, c)
  def assignField(e1 : SetExpr, f : Fields, e2 : SetExpr) : Statement = AssignField(NoMI(), e1, f, e2)
  def `if`(css : (BoolExpr, Statement)*) : Statement = If(NoMI(), css :_*)
  def `for`(x : Vars, m : MatchExpr, s : Statement) : Statement = For(NoMI(), x, m, s)
  def fix(e : SetExpr, s : Statement) : Statement = Fix(NoMI(), e, s)

  def annotateUid(s : Statement) : Statement = {
    val counter = Counter(0)
    def annotateUidH(s : Statement) : Statement = {
      val sMInf = MI(counter.++)
      s match {
        case StmtSeq(_, ss@_*) => StmtSeq(sMInf, ss.map(annotateUidH) :_*)
        case AssignVar(_, x, e) => AssignVar(sMInf, x, e)
        case LoadField(_, x, e, f) => LoadField(sMInf, x, e, f)
        case New(_, x, c) => New(sMInf, x, c)
        case AssignField(_, e1, f, e2) => AssignField(sMInf, e1, f, e2)
        case If(_, cs@_*) => If(sMInf, cs.map(second[(BoolExpr, Statement), Statement].modify(annotateUidH _)) : _*)
        case For(_, x, m, sb) => For(sMInf, x, m, sb)
        case Fix(_, e, sb) => Fix(sMInf, e, sb)
      }
    }
    annotateUidH(s)
  }
}
