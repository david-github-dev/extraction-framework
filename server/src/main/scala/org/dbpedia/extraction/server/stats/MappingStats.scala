package org.dbpedia.extraction.server.stats

import MappingStats.InvalidTarget

/**
 * Contains the statistic of a Template
 */

object MappingStats {
  
  /**
   * Signals that a template or property is mapped but not used in Wikipedia.
   * Currently only used for properties. TODO: also use for templates.
   * We use Int.MaxValue because that means that invalid mappings are sorted first.
   */
  val InvalidTarget = Int.MaxValue
}

class MappingStats(templateStats: TemplateStats, val templateName: String, val isMapped: Boolean, val properties: Map[String, (Int, Boolean)], ignoreList: IgnoreList)
{
  val templateCount = templateStats.templateCount

  /**
   * @param all count all properties or only the ones that are mapped
   * @param use count property use in wikipedia articles or just the definition in template
   */
  private def count(all: Boolean, use: Boolean, invalid:Boolean) = {
    var sum = 0
    for ((name, (count, mapped)) <- properties) {
      if (all || mapped) {
        if (invalid) {
          sum += (if (count == InvalidTarget) 1 else 0)
        } else {
          if (count != InvalidTarget && ! ignoreList.isPropertyIgnored(templateName, name)) {
            sum += (if (use) count else 1)
          }
        }
      }
    }
    sum
  }

  val propertyCount = count(true, false, false)
  val mappedPropertyCount = count(false, false, false) 

  val propertyUseCount = count(true, true, false)
  val mappedPropertyUseCount = count(false, true, false) 
  val mappedPropertyUnuseCount = count(false, false, true) 

  val mappedPropertyRatio = mappedPropertyCount.toDouble / propertyCount.toDouble
  val mappedPropertyUseRatio = mappedPropertyUseCount.toDouble / propertyUseCount.toDouble
}
