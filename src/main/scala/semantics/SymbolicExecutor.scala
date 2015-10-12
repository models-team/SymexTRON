package semantics

/*
Based on "Symbolic Execution with Separation Logic" by Berdine et al. (2005)
 */

import _root_.syntax.ast.MatchExpr._
import syntax.PrettyPrinter
import syntax.ast.ConcreteDesc._
import syntax.ast.QSpatial._
import syntax.ast.SHeap._
import syntax.ast.SMem._
import syntax.ast.SpatialDesc._
import helper._
import semantics.Subst._
import syntax.ast._

import scala.language.postfixOps
import scalaz.Scalaz._
import scalaz.\/.{left, right}
import scalaz._

class SymbolicExecutor(defs: Map[Class, ClassDefinition],
                       kappa: Int = 3, delta: Int = 3, beta: Int = 5) {
  private type StringE[B] = String \/ B

  def match_it(set : SetLit, c : Class, heap: SHeap): String \/ SetLit = set.es.toList.traverseU {
    case Symbol(ident) => for {
      symv <- heap.spatial.get(ident).cata(right, left(s"Unknown symbol: $ident"))
      stc <- subtypes.get(c).cata(cs => right(cs + c), left(s"Unknown class: $c"))
    } yield if (stc.contains(_sd_c.get(symv))) List[BasicExpr](Symbol(ident)) else List()
    case Var(name) => left(s"Unevaluated var $name")
  }.map(l => SetLit(l.flatten : _*))

  def descendants_or_self(set : SetLit, heap: SHeap): String \/ SetLit = set.es.toList.traverseU {
    case e@Symbol(ident) => for {
      symv <- heap.spatial.get(ident).cata(right, left(s"Unknown symbol: $ident"))
      cd <- _sd_concrete.getOption(symv).cata(right, left(s"Not a concrete value: $symv"))
      res <- cd.children.values.toList.traverseU({
        case chldv: SetLit => descendants_or_self(chldv, heap)
        case e2 => left(s"Not a concrete set: $e2")
      }).map(l => l.flatMap(_.es))
    } yield e :: res
    case Var(name) => left(s"Unevaluated var $name")
  }.map(l => SetLit(l.flatten :_*))

  def match_star(set : SetLit, c : Class, heap : SHeap): String \/ SetLit =
    for {
      dcs <- descendants_or_self(set, heap)
      m <- match_it(dcs, c, heap)
    } yield m

  def unfold(sym : Symbols, sd : SpatialDesc, heap: SHeap): String \/ Set[(ConcreteDesc, SHeap)] = {
    def all_children(c : Class) : Map[Fields, (Class, Cardinality)] = {
      val defc = defs(c)
      defc.children ++ defc.supers.map(all_children).foldLeft(Map[Fields, (Class, Cardinality)]())(_ ++ _)
    }
    def all_references(c : Class) : Map[Fields, (Class, Cardinality)] = {
      val defc = defs(c)
      defc.refs ++ defc.supers.map(all_references).foldLeft(Map[Fields, (Class, Cardinality)]())(_ ++ _)
    }
    def freshSetfromCard(c : Cardinality) = {
      c match {
        case Single() => SetLit(Symbol(freshSym))
        case Many() => SetSymbol(freshSym)
      }
    }

    sd match {
      case AbstractDesc(c, unowned) => for {
        defc <- defs.get(c).cata(right, left(s"Class definition of $c is unknown"))
        sts = subtypes(Class(defc.name)) + (Class(defc.name)) //Include self in subtyping
      } yield for {
          st <- sts
          cd = ConcreteDesc(c, all_children(c).mapValues(v => freshSetfromCard(v._2)),
            all_references(c).mapValues(v => freshSetfromCard(v._2)))
          constr = cd.children.foldLeft(Set[BoolExpr]())((constr : Prop, chld : (Fields, SetExpr)) => constr + Eq(ISect(chld._2, unowned), SetLit()))
        } yield (cd, (_sh_spatial.modify(_.updated(sym, cd)) `andThen` _sh_pure.modify(_ ++ constr))(heap))
      // TODO Actually add unonwed constraints
      case cd@ConcreteDesc(c, children, refs) =>
        right(Set((cd, heap)))
    }
  }

  def unfold_all(syms : SetLit, heap: SHeap): String \/ Set[SHeap] = {
    syms.es.toList.foldLeftM[StringE, Set[SHeap]](Set(heap))((heaps: Set[SHeap], b : BasicExpr) => {
      heaps.traverseU(h =>
        for {
          sym <- getSymbol(SetLit(b))
          symv <- h.spatial.get(sym).cata(right, left(s"Unknown symbol $sym"))
          newheaps <- unfold(sym, symv, h).map(_.map(_._2))
        } yield newheaps).map(_.flatten)
                                                             })
  }

  def concretise(el: SetLit, heap: SHeap): String \/ Set[SHeap] = {
    def concretise_final(el: SetLit, heap: SHeap) = {
      el.es.foldLeft(heap.some) { (h: Option[SHeap], b: BasicExpr) =>
        for {
          hh <- h
          sym <- b match {
            case Symbol(id) => id.some
            case Var(name) => none
          }
          symv <- hh.spatial.get(sym)
          cd <- _sd_concrete.getOption(symv)
          defc <- defs.get(cd.c)
          if defc.children.values.forall(_._2.isMany)
        } yield _sh_spatial.modify(_.updated(sym, _cd_children.modify(_.mapValues(v => SetLit()))(cd)))(hh)
      }.toSet
    }
    // TODO Convert all SetLit to expression
    def concretise_helper(el: SetLit, heap: SHeap, depth: Int): String \/ Set[SHeap] = {
      if (depth <= 0) {
        concretise_final(el, heap).right
      }
      else  for {
        unfolded <- unfold_all(el, heap)
        res <- unfolded.traverseU(hh => {
          val childsyms = el.es.flatMap(e =>
            e.asInstanceOf[Symbol].id.|>(hh.spatial.get)
              .flatMap(_sd_concrete.getOption).map(_.children.values).get)
          // Just join everything together
          val joinede = childsyms.foldLeft(SetLit().asInstanceOf[SetExpr])(Union)
          for {
            joinedset_th <- mf.findSet(joinede, beta)
            joinedset_heap = joinedset_th.map(kv =>
              (hh.subst_all(kv._1.map(p => (SetSymbol(p._1), p._2))) |> expand, kv._2))
            concretise_further <- joinedset_heap.traverseU(
              ((hhh : SHeap, els : SetLit) => concretise_helper(els, hhh, depth - 1)).tupled).map(_.flatten)
          } yield concretise_final(el, hh) ++ concretise_further
        })
      } yield res.flatten
    }
    concretise_helper(el, heap, delta)
  }

  def access(sym: Symbols, f: Fields, heap: SHeap): String \/ Set[(SetExpr, SHeap)] = {
    for {
      symv <- heap.spatial.get(sym).cata(right, left(s"Error, unknown symbol $sym"))
      unfolded <- unfold(sym, symv, heap)
      res <- unfolded.map(unf => {
        val (df, newheap) = unf
        if (childfields.contains(f))
          for (chld <- df.children.get(f)) yield (chld, newheap)
        else if (reffields.contains(f))
          for (ref <- df.refs.get(f)) yield (ref, newheap)
        else none
      }.cata(right, left(s"No value for field $f"))).sequenceU
    } yield res
  }

  def disown(heap: SHeap, ee: SetExpr) : SHeap = {
    def disownSD(sym: Symbols, desc: SpatialDesc): SHeap => SHeap  = desc match {
      case AbstractDesc(c, unowned) => _sh_spatial.modify(_.updated(sym, AbstractDesc(c, Union(unowned, ee))))
      case ConcreteDesc(c, children, refs) => _sh_pure.modify(_ ++ (children.foldLeft(Set[BoolExpr]())(
                                                                 (cstr : Prop, chld : (String, SetExpr)) => cstr + Eq(ISect(chld._2, ee), SetLit())))) 
    }
    // TODO consider types when disowning things
    (((h : SHeap) => h.spatial.foldLeft(identity[SHeap] _)((f : SHeap => SHeap, el : (Symbols, SpatialDesc)) =>
        f `andThen` (disownSD _).tupled(el))(h)) `andThen`
      _sh_qspatial.modify(_.map(_qs_unowned.modify(Union(_ ,ee))))) (heap)
  }

  def update(sym: Symbols, f: Fields, ee2: SetExpr, heap: SHeap): String \/ Set[SHeap] = {
    for {
      symv <- heap.spatial.get(sym).cata(right, left(s"Error, unknown symbol $sym"))
      unfolded <- unfold(sym, symv, heap)
      res <- unfolded.map(unf => {
        val (df, newheap) = unf
        if (childfields.contains(f)) for {
          _ <- df.children.get(f).cata(right, left(s"Error, field $f not allocated for symbol $sym"))
        } yield _sh_spatial.modify(_.updated(sym, _cd_children.modify(_.updated(f, ee2))(df)))(disown(newheap, ee2))
        else if (reffields.contains(f)) for {
          _ <- df.refs.get(f).cata(right, left(s"Error, field $f not allocated for symbol $sym"))
        } yield _sh_spatial.modify(_.updated(sym, _cd_refs.modify(_.updated(f, ee2))(df)))(newheap)
        else left(s"Field $f is neither a child nor a reference field")
      }).sequenceU
    } yield res
  }

  def expand(heap: SHeap): SHeap = {
    val (newspatial, newqspatial) = heap.qspatial.foldLeft((heap.spatial, Set[QSpatial]())) {
      (part : (Spatial[Symbols], Set[QSpatial]), qs : QSpatial) => qs.e match {
          // TODO: Use String \/ - instead
        case SetLit(as @_*) =>
          val expanded: Map[Symbols, SpatialDesc] =
            as.map(_.asInstanceOf[Symbol]).map(_.id -> _sd_abstract.reverseGet(AbstractDesc(qs.c, qs.unowned))).toMap
          // TODO: Consider a good way to merge things
          (part._1 ++ expanded, part._2)
        case _ => (part._1, part._2 + qs)
      }
    }
    SHeap(newspatial, newqspatial, heap.pure)
  }


  def execute(pres : Set[SMem], c : Statement) : String \/ Set[SMem] = {
    pres.map[String \/ Set[SMem], Set[String \/ Set[SMem]]] { pre: SMem =>
      c match {
        case StmtSeq(ss@_*) => ss.toList.foldLeftM[StringE, Set[SMem]](Set(pre))(execute)
        case AssignVar(x, e) => for {
          ee <- evalExpr(pre.stack, e)
        } yield Set(SMem(pre.stack + (x -> ee), pre.heap))
        case New(x, c) => for {
          cdef <- defs.get(c).cata(right, left(s"Unknown class: $c"))
          xsym = freshSym
          newspatial =
              xsym -> ConcreteDesc(c, cdef.children.mapValues(_ => SetLit()), cdef.refs.mapValues(_ => SetLit()))
        } yield Set(SMem(pre.stack + (x -> SetLit(Symbol(xsym))),
                    SHeap(pre.heap.spatial + newspatial, pre.heap.qspatial, pre.heap.pure)))
        case LoadField(x, e, f) => for {
          sym <- evalExpr(pre.stack, e).flatMap(getSymbol)
          ares <- access(sym, f, pre.heap)
        } yield ares.map(p => SMem(pre.stack + (x -> p._1), p._2))
        case AssignField(e1, f, e2) => for {
          sym <- evalExpr(pre.stack, e1).flatMap(getSymbol)
          ee2 <- evalExpr(pre.stack, e2)
          newheaps <- update(sym, f, ee2, pre.heap)
        } yield newheaps.map(newheap => SMem(pre.stack, newheap))
        case If(cs@_*) => {
          val ecs    = cs.map(p => (evalBoolExpr(pre.stack, p._1), p._2))
          val newecs = ecs :+ (for {
            other <- ecs.map(_._1).toList.sequenceU
          } yield And(other.map(Not) :_*) -> StmtSeq())
          (for {
            cstmt <- ecs
            (eb, s) = cstmt
            posts = for {
              eeb <- eb
              res <- execute(Set(SMem(pre.stack, SHeap(pre.heap.spatial, pre.heap.qspatial, pre.heap.pure + eeb))), s)
            } yield res
          } yield posts).toSet.sequenceU.map(_.flatten)
          }
        case For(x, m, sb) =>  for {
           // TODO: Figure out how to get meaningful set with new symbols that don't point in the heap
            esols <- for {
               ee <- evalExpr(pre.stack, _me_e.get(m))
               res <- mf.findSet(ee, beta)
             } yield res
            res <- esols.traverseU(esol => {
                val (th, res) = esol
                val newpre = pre.subst_all(th.map(kv => (SetSymbol(kv._1), kv._2))) |> _sm_heap.modify(expand)
                val mres  = m match {
                  case MSet(e) => Set((res, newpre)) |> right
                  case Match(e, c) => for {
                     heaps <- unfold_all(res, newpre.heap)
                     res <- heaps.traverseU(h => for {
                      matches <- match_it(res, c, h)
                     } yield (matches, h))
                  } yield res.map(p => (p._1, _sm_heap.set(p._2)(newpre)))
                  case MatchStar(e, c) => for {
                    heaps <- concretise(res, newpre.heap)
                    res <- heaps.traverseU(h => for {
                      matches <- match_star(res, c, h)
                     } yield (matches, h))
                  } yield res.map(p => (p._1, _sm_heap.set(p._2)(newpre)))
                }
               for {
                mr <- mres
                res <- mr.traverseU({
                (syms : SetLit, mem : SMem) => syms.es.toSet.foldLeftM[StringE, Set[SMem]](Set(mem)) {
                 (mems : Set[SMem], sym : BasicExpr) =>  execute(mems.map(_sm_stack.modify(_ + (x -> SetLit(sym)))), sb)
                }}.tupled).map(_.flatten)
              } yield res
              }).map(_.flatten)
        } yield res
        case Fix(e, sb) => {
          def fixEqCase(bmem: SMem): String \/ Set[SMem] = {
            execute(Set(bmem), sb).flatMap(mems => mems.traverseU(mem => for {
              eebefore <- evalExpr(bmem.stack, e)
              eeafter <- evalExpr(mem.stack, e)
            } yield SMem(mem.stack,
                SHeap(mem.heap.spatial, mem.heap.qspatial, mem.heap.pure + Eq(eebefore, eeafter)))
            ))
          }
          def fixNeqCase(bmem: SMem, k: Int): String \/ Set[SMem] = {
            for {
              imems <- execute(Set(bmem), sb)
              amems = for {
                imem <- imems
                amems = for {
                  eebefore <- evalExpr(bmem.stack, e)
                  eeafter  <- evalExpr(imem.stack, e)
                  newheap = (_sm_heap ^|-> _sh_pure).modify(_ + Not(Eq(eebefore, eeafter))) (imem)
                  amems <- if (k <= 0) fixEqCase(imem) else for {
                      mems2 <- fixEqCase(imem)
                      mems1 <- fixNeqCase(imem, k)
                  } yield mems1 ++ mems2
                } yield amems
              } yield amems
              res <- amems.sequenceU.map(_.flatten)
            } yield res
          }
          fixNeqCase(pre, kappa)
        }
      }
    }.foldLeft(right[String, Set[SMem]](Set())) { (acc, el) =>
      for (acc_ <- acc; el_ <- el) yield acc_ ++ el_
    }
  }

  def getSymbol(e : SetExpr): String \/ Symbols = {
    e match {
      case SetLit(Symbol(sym)) => sym.right
      case _ => left(s"${PrettyPrinter.pretty(e)} is not a symbol")
    }
  }

  def evalBasicExpr(s: SStack, e: BasicExpr): String \/ BasicExpr = e match {
    case Symbol(id) => right(Symbol(id))
    case Var(name) =>
      s.get(name).fold[String \/ BasicExpr](left(s"Error while evaluating expression $e")) {
            case SetLit(evalue) => right(evalue)
            case ee => left(s"Not a basic expression: $ee")
          }
  }

  def evalExpr(s : SStack, e : SetExpr) : String \/ SetExpr = {
      e match {
        case SetLit(es @ _*) =>
          for {
            ees <- es.toList.traverseU(e => evalBasicExpr(s, e))
          } yield SetLit(ees : _*)
        case SetSymbol(ident) => right(SetSymbol(ident))
        case SetVar(name) =>
          // Scalas type inference is really primitive...
          s.get(name).fold[String \/ SetExpr](left(s"Error while evaluating expression $e"))(right)
        case Diff(e1, e2) => for {
          ee1 <- evalExpr(s, e1)
          ee2 <- evalExpr(s, e2)
        } yield Diff(ee1, ee2)
        case Union(e1, e2) => for {
          ee1 <- evalExpr(s, e1)
          ee2 <- evalExpr(s, e2)
        } yield Union(ee1, ee2)
        case ISect(e1, e2) => for {
          ee1 <- evalExpr(s, e1)
          ee2 <- evalExpr(s, e2)
        } yield ISect(ee1, ee2)
      }
    }
  
  def evalBoolExpr(st : SStack, sp : BoolExpr) : String \/ BoolExpr = sp match {
    case Eq(e1, e2) =>
      for {
        ee1 <- evalExpr(st, e1)
        ee2 <- evalExpr(st, e2)
      } yield Eq(ee1, ee2)
    case ClassMem(e1, s) =>
      for {
        ee1 <- evalExpr(st, e1)
      } yield ClassMem(ee1, s)
    case Not(p) =>
      for {
        ep <- evalBoolExpr(st, p)
      } yield Not(ep)
    case And(ps@_*) =>
      for {
        eps <- ps.map(evalBoolExpr(st, _)).toList.sequenceU
      } yield And(eps :_*)
    case SetMem(e1, e2) =>
      for {
        ee1 <- evalBasicExpr(st, e1)
        ee2 <- evalExpr(st, e2)
      } yield SetMem(ee1, ee2)
    case SetSub(e1, e2) =>
      for {
        ee1 <- evalExpr(st, e1)
        ee2 <- evalExpr(st, e2)
      } yield SetSub(ee1, ee2)
    case SetSubEq(e1, e2) =>
      for {
        ee1 <- evalExpr(st, e1)
        ee2 <- evalExpr(st, e2)
      } yield SetSubEq(ee1, ee2)
  }

  private val symCounter = Counter(0)

  private def freshSym: Symbols = symCounter++

  private val mf = new ModelFinder(symCounter, defs)


  private val childfields: Set[Fields] = defs.values.flatMap(_.children.keys).toSet
  private val reffields: Set[Fields]   = defs.values.flatMap(_.refs.keys).toSet
  private val subtypes: Map[Class, Set[Class]] = defs.mapValues(_ => Set[Class]()) ++ {
    defs.values.foldLeft(Map[Class, Set[Class]]())((m : Map[Class, Set[Class]], cd: ClassDefinition) =>
      cd.supers.foldLeft(m)((m_ : Map[Class, Set[Class]], sup : Class) => m_.adjust(sup)(_ + Class(cd.name)))
    ).trans
  }


  {
    val commoncr = childfields intersect reffields
    assert(commoncr.isEmpty, s"There are overlapping names used for fields and references: ${commoncr.mkString(", ")}")
  }
}
