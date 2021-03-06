package services

import java.util.Properties

import edu.stanford.nlp.ling.CoreAnnotations.{SentencesAnnotation, TextAnnotation, TokensAnnotation}
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.util.CoreMap

import scala.collection.JavaConverters._
import model.TextRange

case class WordInSentence(sentence: String, word: String, range: TextRange)

/**
  * A service to extract proper names from documents.
  */
class SentenceHelpers() {
  val props: Properties = new Properties()
  props.put("annotators", "tokenize, ssplit")
  val pipeline: StanfordCoreNLP = new StanfordCoreNLP(props)

  def getFirstWordsInSentences(text: String): List[WordInSentence] = {
    val document: Annotation = new Annotation(text)
    pipeline.annotate(document)

    val sentences: List[CoreMap] = document.get(classOf[SentencesAnnotation]).asScala.toList

    for {
      sentence: CoreMap <- sentences
    } yield {
      val firstToken = sentence.get(classOf[TokensAnnotation]).asScala.toList.head
      val word = firstToken.get(classOf[TextAnnotation])
      WordInSentence(
        sentence.toString,
        word,
        TextRange(firstToken.beginPosition(), firstToken.endPosition())
      )
    }
  }
}
