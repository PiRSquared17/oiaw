package eu.budabe.oiaw

import scala.util.parsing.combinator.syntactical._
import scala.util.parsing.combinator._
import scala.util.matching.Regex
//import scala.util.matching.Match

import scala.io._

trait LineParser {
  type A
  def parse_lines(line : String, lines : List[String], 
                  start_re : Regex, attr_re : Regex, 
                  f : (Regex.Match) => A) : List[A] = {
     def parse_attrs(l : String, ls : List[String], attrs : List[A]) : List[A] = {
       ls match {
         case x :: xs if attr_re.findFirstMatchIn(l).isDefined => {
           val res = f(attr_re.findFirstMatchIn(l).get)
           parse_attrs(x, xs, res :: attrs)
         }
         case Nil if attr_re.findFirstMatchIn(l).isDefined => {
           val res = f(attr_re.findFirstMatchIn(l).get)
           res :: attrs
         }
         case _ => attrs
       }
     }
     lines match {
       case x :: xs if start_re.findFirstMatchIn(line).isDefined =>
         parse_attrs(x, xs, Nil).reverse // present them in their original sequence
       case x :: xs => parse_lines(x, xs, start_re, attr_re, f)
       case Nil => Nil
     }
                   
  }

  def get_group(hit : Regex.Match, groupname : String) : String = {
    try {
      hit.group(groupname).trim
    } catch {
      case nsee : NoSuchElementException => ""
    }
  }
}

class PropertyParser(val start_properties_regexp : Regex,
                     val properties_regexp : Regex) extends LineParser {
  type A = Property


  /**
   * Extracts the list of properties from the class definition and returns it
   *
   * The algorithm works on the assumption that the properties follow each other in a given
   * text fragment, marked on the one side by the properties-header (e.g. a table header) and
   * on the other side by an end marker (e.g. end of table or new lines)
   */
  def parse_properties(range_class_id : String, lines : List[String]) : List[Property] = {
    parse_lines("", lines, start_properties_regexp, properties_regexp, 
                {hit => new Property(get_group(hit, "property-name"), 
                                     get_group(hit, "property-description"), 
                                     get_group(hit, "property-id"),
		                     get_group(hit, "alternate-id"), 
				     range_class_id, 
                                     get_group(hit, "value-domain"),
				     get_group(hit, "cardinality"))})
  }
}

class RelationshipParser(val start_relationships_regexp : Regex,
                         val relationships_regexp : Regex) extends LineParser {
  type A = Relationship


  /**
   * Extracts the list of relationships from the class definition and returns it
   *
   * The algorithm works on the assumption that the properties follow each other in a given
   * text fragment, marked on the one side by the properties-header (e.g. a table header) and
   * on the other side by an end marker (e.g. end of table or new lines)
   */
  def parse_relationships(range_class_id : String, lines : List[String]) : List[Relationship] = {
    parse_lines("", lines, start_relationships_regexp, relationships_regexp, 
	              { hit => new Relationship(get_group(hit, "relationship-name"), 
						get_group(hit, "relationship-description"), 
						get_group(hit, "relationship-id"),
			                        range_class_id,
			                        get_group(hit, "player-type1").trim, 
						get_group(hit, "role-type1"),
			                        get_group(hit, "player-type2"), 
						get_group(hit, "role-type2"),
						get_group(hit, "relationship-characteristics"),
						get_group(hit, "cardinality"))})
	}
}

class TopicParser(val start_class_regexp : Regex, 
		  val class_regexp : Regex,
		  val rp : RelationshipParser,
		  val pp : PropertyParser) extends LineParser {
  type A = Topic

  def parse_topic(lines : List[String]) : List[Topic] = {
    parse_lines("", lines, start_class_regexp, class_regexp, 
		{ hit =>
		  val properties = pp.parse_properties(get_group(hit, "class-id"), lines)
		 val relationships = rp.parse_relationships(get_group(hit, "class-id"), lines)
     println("Treating class " + get_group(hit, "class-id"))
		 new Topic(get_group(hit, "classname"), 
               get_group(hit, "class-id"),
               get_group(hit, "class-description"),
               get_group(hit, "subclass-of"),
		           properties, relationships)})
  }
}

//Implementation strategy:
//Parse template file to create regular expressions (open as Java properties file?)


//Split input files into separate parts corresponding to individual class declarations
//Use Regular Expressions extractors to extract the variables

class WikiParser(val wikipage : Source, val template_name : String) {
  //A subsection contains a single class declaration
  //A class definition consists of three parts
  // * a subclass declaration
  // * a list of properties
  // * a list of relationships

  val template = load_template(template_name)
  val class_strings = split_classes(wikipage)

  val start_class_regexp = build_regexp("class-header")
  val class_regexp = build_regexp("class-template")

  val rp = new RelationshipParser(build_regexp("relationships-header"),
                                  build_regexp("relationships-template"))
  val pp = new PropertyParser(build_regexp("properties-header"),
                              build_regexp("properties-template"))
  

  /**
   * Build the class definition with its properties and relationships and returns it
   * (or None, if no class definition could be found in the text fragment)
   */
  def parse_class(lines : List[String]) : Option[Topic] = {
    val tp = new TopicParser(start_class_regexp, class_regexp, rp, pp)
    
    val tops : List[Topic] = tp.parse_topic(lines)

    tops match {
      case top :: ts => Some(top)
      case Nil => None
    }
  }

  def get_topics() : List[Topic] = {
    for {
      s <- class_strings
      top = parse_class(s)
      if top.isDefined
    } yield top.get
  }

  def load_template(filename: String) : java.util.Properties = {
    var template = new java.util.Properties()
    template.load(new java.io.FileReader(filename))
    
    template
  }
  
  def split_classes(src : Source) : List[List[String]] = {
    val src_lines = src.getLines.toList
    val re = new Regex(template.getProperty("class-separator") + """\s*$""")
    Utility.splitBefore(src_lines, {line => re.findFirstMatchIn(line).isDefined})
  }
  
  def build_regexp(property : String) : Regex = {
    val brute_regexp : String = template.getProperty(property)

    val regexp = """\{[\w-]+?\}""".r.replaceAllIn(brute_regexp, """([^|\\n]*)""") + """\s*"""
    val fieldnames =   (for {
      hit <- """\{([\w-]+)\}""".r findAllIn(brute_regexp) matchData
     } yield hit.group(1)) toList

    new Regex (regexp, fieldnames :_*)
  }

}


