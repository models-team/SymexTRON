package examples

import semantics.domains._
import syntax.ast.Statement._
import syntax.ast._

trait Class2TableExample extends Example {

  val sourceClassDefs = Set(
    new ClassDefinition("Class", Map("attributes" -> ((Class("Attribute"), Many))), Map()),
    new ClassDefinition("Attribute", Map(), Map("type" -> ((Class("String"), Single))))
  )
  val targetClassDefs = Set(
    new ClassDefinition("Table", Map("columns" -> ((Class("Column"), Many))),
      Map("id" -> (Class("IdColumn"), Single))),
    new ClassDefinition("Column", Map(), Map()),
    new ClassDefinition("IdColumn", Map(), Map(), Some(Class("Column"))),
    new ClassDefinition("DataColumn", Map(), Map("type" -> (Class("String"), Single)), Some(Class("Column")))
  )
  override val classDefs = Shared.stdClassDefs ++ sourceClassDefs ++ targetClassDefs

  override val pres = {
    val stack = Map("class" -> SetLit(Seq(Symbol(-1))))
    Set(
      SMem(stack, stack,
        SHeap.initial(Map(), Map(Symbol(-1) -> UnknownLoc(Class("Class"), SUnowned)), Map(), Map(), Set()))
    )
  }

}

object Class2TableSimpleExample extends Class2TableExample {
  override val prog = stmtSeq(
    `new`("table", Class("Table")),
    `new`("idcol", Class("IdColumn")),
    assignField(SetLit(Seq(Var("table"))), "id", SetLit(Seq(Var("idcol")))),
    assignField(SetLit(Seq(Var("table"))), "columns", SetLit(Seq(Var("idcol")))),
    loadField("class_attributes", SetLit(Seq(Var("class"))), "attributes"),
    `for`("attr", MSet(SetVar("class_attributes")), stmtSeq(
      `new`("col", Class("DataColumn")),
      loadField("attrtype", SetLit(Seq(Var("attr"))), "type"),
      assignField(SetLit(Seq(Var("col"))), "type", SetLit(Seq(Var("attrtype")))),
      loadField("tablecolumns", SetLit(Seq(Var("table"))), "columns"),
      assignField(SetLit(Seq(Var("table"))), "columns", Union(SetVar("tablecolumns"), SetLit(Seq(Var("col")))))
    ))
  )
}

object Class2TableDeepMatchingExample extends Class2TableExample {
  override val prog = stmtSeq(
    `new`("table", Class("Table")),
    `new`("idcol", Class("IdColumn")),
    assignField(SetLit(Seq(Var("table"))), "id", SetLit(Seq(Var("idcol")))),
    assignField(SetLit(Seq(Var("table"))), "columns", SetLit(Seq(Var("idcol")))),
    `for`("attr", MatchStar(SetLit(Seq(Var("class"))), Class("Attribute")), stmtSeq(
      `new`("col", Class("DataColumn")),
      loadField("attrtype", SetLit(Seq(Var("attr"))), "type"),
      assignField(SetLit(Seq(Var("col"))), "type", SetLit(Seq(Var("attrtype")))),
      loadField("tablecolumns", SetLit(Seq(Var("table"))), "columns"),
      assignField(SetLit(Seq(Var("table"))), "columns", Union(SetVar("tablecolumns"), SetLit(Seq(Var("col")))))
    ))
  )
}