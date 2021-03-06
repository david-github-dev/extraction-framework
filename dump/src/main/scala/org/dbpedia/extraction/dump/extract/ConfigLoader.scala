package org.dbpedia.extraction.dump.extract

import java.io._
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

import org.dbpedia.extraction.config.provenance.Dataset
import org.dbpedia.extraction.destinations._
import org.dbpedia.extraction.dump.download.Download
import org.dbpedia.extraction.mappings._
import org.dbpedia.extraction.ontology.Ontology
import org.dbpedia.extraction.ontology.io.OntologyReader
import org.dbpedia.extraction.sources.{Source, WikiSource, XMLSource}
import org.dbpedia.extraction.util.RichFile.wrapFile
import org.dbpedia.extraction.util._
import org.dbpedia.extraction.wikiparser._

import scala.collection.convert.decorateAsScala._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Loads the dump extraction configuration.
 * 
 * TODO: clean up. The relations between the objects, classes and methods have become a bit chaotic.
 * There is no clean separation of concerns.
 * 
 * TODO: get rid of all config file parsers, use Spring
 */
class ConfigLoader(config: Config)
{
  private val logger = Logger.getLogger(classOf[ConfigLoader].getName)

  private val extractionJobs = new ConcurrentHashMap[Language, ExtractionJob]().asScala

  private val extractionRecorder = new mutable.HashMap[Language, ExtractionRecorder[WikiPage]]()

  //these lists are used when the ImageExtractor is amongst the selected Extractors
  private val nonFreeImages = new ConcurrentHashMap[Language, Seq[String]]().asScala
  private val freeImages = new ConcurrentHashMap[Language, Seq[String]]().asScala

  def getExtractionRecorder(lang: Language): ExtractionRecorder[WikiPage] = {
    extractionRecorder.get(lang) match {
      case None =>
        extractionRecorder(lang) = config.logDir match {
          case Some(p) =>
            var logname = config.configPath.replace("\\", "/")
            logname = logname.substring(logname.lastIndexOf("/") + 1)
            logname = logname + "_" + lang.wikiCode + ".log"
            val logFile = new File(p, logname)
            logFile.createNewFile()
            val logStream = new FileOutputStream(logFile)

            //TODO val preamble = input._1.wikiCode+": "+input._2.size+" extractors ("+
            //  input._2.map(_.getSimpleName).mkString(",")+"), "+
            //  datasets.size+" datasets ("+datasets.mkString(",")+")"

            new ExtractionRecorder[WikiPage](new OutputStreamWriter(logStream), 2000, null, config.slackCredentials.getOrElse(null))
          case None => new ExtractionRecorder[WikiPage](null, 2000, null, config.slackCredentials.getOrElse(null))
        }
        extractionRecorder(lang)
      case Some(er) => er
    }
  }

  /**
    * Creates ab extraction job for a specific language.
    */
  val extractionJobWorker: Workers[(Language, Seq[Class[_ <: Extractor[_]]])] = SimpleWorkers(config.parallelProcesses, config.parallelProcesses) { input: (Language,  Seq[Class[_ <: Extractor[_]]]) =>

    val finder = new Finder[File](config.dumpDir, input._1, config.wikiName)

    val date = latestDate(finder)

    //Extraction Context
    val context = new DumpExtractionContext
    {
      def ontology: Ontology = _ontology

      def commonsSource: Source = _commonsSource

      def language: Language = input._1

      private lazy val _mappingPageSource =
      {
        val namespace = Namespace.mappings(language)

        if (config.mappingsDir != null && config.mappingsDir.isDirectory)
        {
          val file = new File(config.mappingsDir, namespace.name(Language.Mappings).replace(' ','_')+".xml")
          XMLSource.fromFile(file, Language.Mappings)
        }
        else
        {
          val namespaces = Set(namespace)
          val url = new URL(Language.Mappings.apiUri)
          WikiSource.fromNamespaces(namespaces,url,Language.Mappings)
        }
      }

      def mappingPageSource : Traversable[WikiPage] = _mappingPageSource

      private lazy val _mappings =
      {
        MappingsLoader.load(this)
      }
      def mappings : Mappings = _mappings

      def articlesSource: Source = getArticlesSource(language, finder)

      private val _redirects =
      {
        finder.file(date, "template-redirects.obj") match{
          case Some(cache) => Redirects.load(articlesSource, cache, language)
          case None => new Redirects(Map())
        }

      }

      def redirects : Redirects = _redirects

      private val _disambiguations =
      {
        try {
          Disambiguations.load(reader(finder.file(date, config.disambiguations).get), finder.file(date, "disambiguations-ids.obj").get, language)
        } catch {
          case ex: Exception =>
            logger.info("Could not load disambiguations - error: " + ex.getMessage)
            null
        }
      }

      def disambiguations : Disambiguations =
        if (_disambiguations != null)
          _disambiguations
        else
          new Disambiguations(Set[Long]())

      def configFile: Config = config

      def freeImages : Seq[String] = ConfigLoader.this.freeImages.get(language) match{
        case Some(s) => s
        case None => Seq()
      }

      def nonFreeImages : Seq[String] = ConfigLoader.this.nonFreeImages.get(language) match{
        case Some(s) => s ++ ConfigLoader.this.nonFreeImages(Language.Commons)                //always add commons to the list of non free images
        case None => Seq()
      }
    }

    //Extractors
    val extractor = CompositeParseExtractor.load(input._2, context)
    val datasets = extractor.datasets

    val formatDestinations = new ArrayBuffer[Destination]()

    for ((suffix, format) <- config.formats) {
      val datasetDestinations = new mutable.HashMap[Dataset, Destination]()
      for (dataset <- datasets) {
        finder.file(date, dataset.encoded.replace('_', '-')+'.'+suffix) match{
          case Some(file)=> datasetDestinations(dataset) = new DeduplicatingDestination(new WriterDestination(writer(file), format))
          case None =>
        }
      }
      formatDestinations += new DatasetDestination(datasetDestinations)
    }

    val destination = new MarkerDestination(
      new CompositeDestination(formatDestinations: _*),
      finder.file(date, Extraction.Complete).get,
      false
    )

    val extractionJobNS = if(context.language == Language.Commons)
      ExtractorUtils.commonsNamespacesContainingMetadata
    else config.namespaces

    val extractionJob = new ExtractionJob(
      extractor,
      context.articlesSource,
      extractionJobNS,
      destination,
      context.language,
      config.retryFailedPages,
      getExtractionRecorder(context.language)
    )

    extractionJobs.put(context.language, extractionJob)
  }

  /**
    * Creates ab extraction job for a specific language.
    */
  val imageCategoryWorker: Workers[Language] = SimpleWorkers(config.parallelProcesses, config.parallelProcesses) { lang: Language =>
    getExtractionRecorder(lang).initialize(lang, "Image Extraction")
    getExtractionRecorder(lang).printLabeledLine("Start image list preparation for ImageExtractor.", RecordSeverity.Info)
    val finder = new Finder[File](config.dumpDir, lang, config.wikiName)
    val imageCategories = ConfigUtils.loadImages(getArticlesSource(lang, finder), lang.wikiCode, getExtractionRecorder(lang))
    this.freeImages.put(lang, imageCategories._1)
    this.nonFreeImages.put(lang, imageCategories._2)
    getExtractionRecorder(lang).printLabeledLine("Finished image list preparation for ImageExtractor: " + imageCategories._1.size + " free images, " + imageCategories._2 + " nonfree images.", RecordSeverity.Info)
  }


    /**
     * Loads the configuration and creates extraction jobs for all configured languages.
      *
      * @return Non-strict Traversable over all configured extraction jobs i.e. an extractions job will not be created until it is explicitly requested.
     */
    def getExtractionJobs: Traversable[ExtractionJob] =
    {
      //first check if some langs are extraction images, if so load the image category lists

      val zw = config.extractorClasses.filter(x => x._2.map( y => y.getSimpleName).contains("ImageExtractor"))
      val imageExtractorLanguages = zw match{
        case filtered if filtered.nonEmpty => filtered.keySet.toList ++ List(Language.Commons)       //else: add Commons (see ImageExtractorScala for why)
        case _ => List[Language]()                                                                   //no ImageExtractors selected
      }
      Workers.work[Language](imageCategoryWorker, imageExtractorLanguages)

      // Create a non-strict view of the extraction jobs
      // non-strict because we want to create the extraction job when it is needed, not earlier
      Workers.work[(Language, Seq[Class[_ <: Extractor[_]]])](extractionJobWorker, config.extractorClasses.toList)
      extractionJobs.values
    }

    private def writer(file: File): () => Writer = {
      () => IOUtils.writer(file)
    }

    private def reader(file: File): () => Reader = {
      () => IOUtils.reader(file)
    }

    private def readers(source: String, finder: Finder[File], date: String): List[() => Reader] = {
      files(source, finder, date).map(reader)
    }

    private def files(source: String, finder: Finder[File], date: String): List[File] = {

      val files = if (source.startsWith("@")) { // the articles source is a regex - we want to match multiple files
        finder.matchFiles(date, source.substring(1))
      } else List(finder.file(date, source)).collect{case Some(x) => x}

      logger.info(s"Source is $source - ${files.size} file(s) matched")

      files
    }

    //language-independent val
    private lazy val _ontology =
    {
        val ontologySource = if (config.ontologyFile != null && config.ontologyFile.isFile)
        {
          XMLSource.fromFile(config.ontologyFile, Language.Mappings)
        } 
        else 
        {
          val namespaces = Set(Namespace.OntologyClass, Namespace.OntologyProperty)
          val url = new URL(Language.Mappings.apiUri)
          val language = Language.Mappings
          WikiSource.fromNamespaces(namespaces, url, language)
        }
        new OntologyReader().read(ontologySource)
    }

    //language-independent val
    private lazy val _commonsSource =
    {
      val finder = new Finder[File](config.dumpDir, Language("commons"), config.wikiName)
      val date = latestDate(finder)
      XMLSource.fromReaders(config.source.flatMap(x => readers(x, finder, date)), Language.Commons, _.namespace == Namespace.File)
    }

  private def getArticlesSource(language: Language, finder: Finder[File]) =
  {
    val articlesReaders = config.source.flatMap(x => readers(x, finder, latestDate(finder)))

    XMLSource.fromReaders(articlesReaders, language,
      title => title.namespace == Namespace.Main || title.namespace == Namespace.File ||
        title.namespace == Namespace.Category || title.namespace == Namespace.Template ||
        title.namespace == Namespace.WikidataProperty || ExtractorUtils.titleContainsCommonsMetadata(title))
  }

  private def latestDate(finder: Finder[_]): String = {
    val isSourceRegex = config.source.startsWith("@")
    val source = if (isSourceRegex) config.source.head.substring(1) else config.source.head
    val fileName = if (config.requireComplete) Download.Complete else source
    finder.dates(fileName, isSuffixRegex = isSourceRegex).last
  }
}

