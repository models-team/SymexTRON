package testing

import semantics.domains.{Instances, CMem}
import _root_.syntax.ast.{Single, Fields, Class, ClassDefinition}

import scalaz._, Scalaz._

/**
  * Created by asal on 01/03/2016.
  */
class MetaModelCoverage(defs: Map[Class, ClassDefinition]) {
  def relevantPartialCoverage(inputTypes: Set[Class], mems: Set[CMem]) = {
    def relevantFeatures(todoClasses: Set[Class],
                         visitedClasses: Set[Class],
                         relevantFields: Set[(Class, Fields)]): (Set[Class], Set[(Class, Fields)]) = {
      if (todoClasses.isEmpty) (visitedClasses, relevantFields)
      else {
        val clazz = todoClasses.head
        val classDef = defs(clazz)
        val fields = (classDef.children.keys ++ classDef.refs.keys).toSet
        val reachedByOwnership = classDef.children.values.map(_._1).toSet
        val reachedByRequiredRef = classDef.refs.values.collect { case (c, Single) => c }.toSet
        val newTodoClasses: Set[Class] = (todoClasses.tail ++ reachedByOwnership ++ reachedByRequiredRef) diff (visitedClasses + clazz)
        relevantFeatures(newTodoClasses, visitedClasses + clazz, relevantFields ++ fields.map(clazz -> _))
      }
    }
    val (relevantClasses, relevantFields) = relevantFeatures(inputTypes, Set[Class](),Set[(Class,Fields)]())
    val heaps = mems.map(_.heap)
    val coveredClasses = heaps.flatMap(h => h.typeenv.values)
    val coveredFields = heaps.flatMap { h =>
      h.typeenv.foldLeft(Set[(Class, Fields)]()) { (st, instinfo) =>
        val (inst, clazz) = instinfo
        def covered(env: Map[Instances, Map[Fields, Set[Instances]]]) : Set[(Class, Fields)] = {
          env.get(inst).cata(_.keySet, Set[Fields]()).map(f => defs.definingClass(clazz, f) -> f)
        }
        st ++ covered(h.childenv) ++ covered(h.refenv)
      }
    }
    (relevantClasses diff coveredClasses, relevantFields diff coveredFields)
  }

}