


package synchrony.iterators

/** Provide EFile to represent and process files on disk.
 *
 *  An EFile is intended to represent a large file on disk.
 *  It provides functionalities for manipulating, serializing,
 *  deserializing, and sorting the file.  It provides 
 *  Synchrony iterators and aggregate functions on file.
 *
 *
 * Wong Limsoon
 * 20 May 2021
 */



object FileCollections {
  
  import scala.reflect.ClassTag 
  import java.nio.file.{ Files, Paths, StandardCopyOption }
  import java.io.{ EOFException } 
  import synchrony.iterators.SyncCollections
  import synchrony.iterators.AggrCollections
  import synchrony.iterators.Serializers._

  var DEBUG     = false
  var SZLIMIT   = 2000000L
  var AUTOSLURP = false


  case class FileNotFound(ms:String) extends Throwable
  case class FileCannotSave(ms:String) extends Throwable



  // TMPDIR is the directory for keeping short-term serialized files
  // ANSDIR is the directory for keeping longer-term temp serialized files

  var TMPDIR: java.nio.file.Path = null   // = "synchrony-wrk/tmp"
  var ANSDIR: java.nio.file.Path = null   // = "synchrony-wrk/ans"

  def newTMPDIR() = {
    val dir = Files.createTempDirectory("synchrony-wrk")
    TMPDIR = Files.createDirectory(Paths.get(dir.toString + "/tmp"))
    ANSDIR = Files.createDirectory(Paths.get(dir.toString + "/ans"))
  }

  newTMPDIR()



  // Aggregates/OpG provides many commonly used aggregate functions.
  // EIterator/SynchroEIterator provides iterators on large files.
  // Put a copy of them here so that user of this EFile
  // module can easily find them.
   
  type Aggregates[A] = AggrCollections.Aggregates[A]
  val OpG            = AggrCollections.OpG

  val EIterator      = SyncCollections.EIterator
  type EIterator[A]  = SyncCollections.EIterator[A]

  val SynchroEIterator = SyncCollections.SynchroEIterator
  type SynchroEIterator[A, B, C] = SyncCollections.SynchroEIterator[A, B, C]

  

  /** EFileState keeps track of the state of an EFile.
   *
   *  EFile can be on disk, in memory, or is transient.
   *  These states are captured by three subtypes of EFileState.
   */

  sealed trait EFileState[A] {
    val settings: EFileSettings[A]
    def toEFile: EFile[A] = EFile[A](this)
  }


  /** EFileState when the EFile is on disk.
   *
   *  @val filename is name of the file.
   *  @val settings records various settings of the EFile.
   */

  final case class OnDisk[A] (
    filename: String,
    override val settings: EFileSettings[A])
  extends EFileState[A] 



  /** EFileState when the EFile is a raw String.
   *
   *  @val raw      is the String representing an EFile.
   *  @val filename is name of the EFile.
   *  @val settings records various settings of the EFile.
   */

  final case class Slurped[A](
    raw: String,
    filename: String,
    override val settings: EFileSettings[A]) 
  extends EFileState[A]


  /** EFileState when the EFile is in memory.
   *
   *  @val entries  are the entries of the EFile, loaded in memory.
   *  @val settings records various settings of the EFile.
   */

  final case class InMemory[A](
    entries: Vector[A], 
    override val settings: EFileSettings[A]) 
  extends EFileState[A]


  /** EFileState when the EFile is transient. I.e., the EFile may 
   *  be a temporary result, and it can only be used once, unless 
   *  it gets converted to on-disk or in-memory EFile.
   *
   *  @val entries  are the entries of the EFile, to be realized
   *                from an iterator one at a time.
   *  @val settings records various settings of the EFile.
   */
   
  final case class Transient[A](
    entries: Iterator[A], 
    override val settings: EFileSettings[A]) 
  extends EFileState[A]
    


  /** EFileSettings keep various settings of an EFile.
   */

  case class EFileSettings[A](
    prefix: String,         // Prefix for filename
    suffixtmp: String,      // Suffix for temp file
    suffixsav: String,      // Suffix for saved file
    aveSz: Int,             // Estimated size of an EFile entry.
    cardCap: Int,           // Cardinality limit to force serialization
    ramCap: Long,           // RAM to use for internal sorting
    cap: Int,               // Cardinality limit for internal sorting
    doSampling: Boolean,    // Use sampling to guess cap
    samplingSz: Int,        // # of items to sample
    alwaysOnDisk: Boolean,  // Force serialization at all sizes;

    totalsizeOnDisk: EFile[A] => Double,
                            // Compute size of EFile, including sub files.

    serializer: Serializer[A],
                            // Serializer to be used for writing 
                            // this EFile out.

    deserializer: Deserializer[A],
                            // Deserializer to be used for reading an EFile.

    cmp: Ordering[A],       // Ordering to use on elements in the EFile.
    classTag: ClassTag[A] 
  )



  /** Constructor for EFile.
   *
   *  An EFile is intended to represent a large file on disk.
   *  It provides functionalities for manipulating, serializing,
   *  deserializing, and sorting the file.  It provides 
   *  Synchrony iterators and aggregate functions on file.
   *
   *  @val efile is the EFileState of this EFile.
   */

  case class AnEFile[A](override val efile: EFileState[A])
  extends EFile[A]


  trait EFile[A] extends Aggregates[A] {

    val efile: EFileState[A]


  /** Inherit aggregate functions by defining itC from Aggregates.
   */

    override def itC: Iterator[A] = eiterator


  //
  // The settings, serializer, and deserializer of this EFile.
  // And cap is a hint on the max # of elements of this EFile
  // that should be kept in memory during sorting.
  //

    implicit private val settings     = efile.settings
    private val serializer   = settings.serializer
    private val deserializer = settings.deserializer
    private val cap          = settings.cap 
    implicit val ct          = settings.classTag
    implicit val cmp         = settings.cmp  
    


  //
  // Endowing this EFile with Synchrony iterators
  //


  /** @return an EIterator on this EFile.
   */
    def eiterator: EIterator[A] = efile match {
      case ef: InMemory[A] => EIterator[A](ef.entries)
      case ef: Transient[A] => EIterator[A](ef.entries)
      case ef: Slurped[A] => deserializer(ef.raw, Some(ef.filename)) 
      case ef: OnDisk[A] => deserializer(ef.filename)
    }


  /** @return a slurped EIterator on this EFile. A slurped EIterator
   *     slurps the file into memory in one go, provided the file
   *     is small ( SZLIMIT)
   */

    def slurpedEIterator: EIterator[A] = slurped.eiterator



  //
  // Methods for manipulating and processing EFiles.
  //


 /** @return whether this EFile is empty
  */

    def isEmpty: Boolean = processedWith { it => !it.hasNext }



  /** Return the n-th element in this EFile.
   *
   *  @param n is position of the element to fetch, starting from 0.
   *  @return the n-th element in this EFile.
   */

    def apply(n: Int): A = efile match {
      case ef: InMemory[A] => ef.entries(n)
      case _               => processedWith { it => it.drop(n); it.next() }
    }



  /** Select some elements in this EFile.
   *
   *  @param f is the selection predicate.
   *  @return the selected elements of this EFile.
   */

    def filtered(f: A => Boolean): EFile[A] = {
      val filter = eiterator.filter(f)
      EFile.transientEFile[A](filter)
    }



  /** Process all elements in this EFile using a given function.
   *
   *  @param f is the processing function.
   *  @return the processed result.
   */

    def processedWith[B](f: EIterator[A] => B): B = {
      val it = eiterator
      try f(it) finally it.close()
    }



  /** @return name of this EFile.
   */

    def filename: String = efile match {
      case ef: InMemory[A]  => ""
      case ef: Transient[A] => ""
      case ef: Slurped[A]   => ef.filename
      case ef: OnDisk[A]    => ef.filename
    }



  /** @return size of this EFile. If this EFile has components that
   *  point to other files, the size of these other files
   *  are ignored.
   */

    def filesize: Long = efile match {
      case ef: InMemory[A]  => 0L
      case ef: Transient[A] => 0L
      case ef: Slurped[A]   => ef.raw.length
      case ef: OnDisk[A]    =>
        try Files.size(Paths.get(ef.filename))
        catch { case _ : Throwable => throw FileNotFound(ef.filename) }
    }



  /** @return size of this EFile on disk, inclusive of subcomponents
   *  kept in separate files.
   */
 
    def totalsizeOnDisk: Long = settings.totalsizeOnDisk(this).toLong



  /** If this EFile is on disk, and is small, slurp it into memory.
   *
   *  @return the slurped EFile.
   */

    def slurped: EFile[A] = efile match {

      case ef: InMemory[A]  => this

      case ef: Transient[A] => this

      case ef: Slurped[A]   => 
        EFile.inMemoryEFile[A](deserializer(ef.raw, Some(ef.filename)).toVector)

      case ef: OnDisk[A] => (filesize < SZLIMIT || AUTOSLURP) match { 

        // file is big, dont slurp it.
        case false => this

        // file is small, slurp it.
        case true =>
          if (DEBUG) { println(s"** Slurping file = ${ef.filename}") }
          val bytes = Files.readAllBytes(Paths.get(ef.filename))
          val text = new String(bytes)
          EFile.slurpedEFile[A](text, ef.filename)
       }
    }



  /** If this EFile is transient, depending on its estimated size,
   *  store it in memory or on disk.  
   *
   *  @return the EFile stored in memory or on disk.
   */

    def stored: EFile[A] = efile match {

      case ef: InMemory[A]  => this

      case ef: Slurped[A]   => this

      case ef: OnDisk[A]    => this

      case ef: Transient[A] => processedWith { it =>
      
        val sampling = it.lookahead(settings.cardCap)

        (!settings.alwaysOnDisk && sampling.length < settings.cardCap) match { 

          case true  => 
            // EFile appears small; keep in memory.
            InMemory(sampling, settings).toEFile

          case false =>
            // EFile appears a bit large; keep on disk
            val filename = Files
                           .createTempFile(TMPDIR, settings.prefix, settings.suffixtmp)
                           .toString
            serializer(it, filename)
            OnDisk[A](filename, settings).toEFile
        }
      }
    }



  /** Serialize this EFile to disk.
   *
   *  @param  folder is the folder to serialize this EFile to.
   *                 if folder is "", use TMPDIR
   *  @return the EFile on disk.
   */
  
    def serialized(implicit folder: String = ""): EFile[A] = {

      val filename = {
        val pfix = settings.prefix
        val sfix = settings.suffixtmp
        val dir = folder match {
          case ""  => TMPDIR
          case d   => Files.createDirectories(Paths.get(d))
        }
        Files.createTempFile(dir, pfix, sfix).toString
      }

      (folder, efile) match {
        case ("", ef: OnDisk[A]) => 
          // EFile is already on disk. Do nothing 
          this 
        case _ => 
          // EFile is in memory or is transient, 
          // or a folder is specified. Store to disk
          processedWith { it => serializer(it, filename) } 
          OnDisk[A](filename, settings).toEFile
      }
    }

      

  /** Delete the EFile, if it is on disk.
   */

    def destruct(): Unit = try { 
      efile match {
        case ef: OnDisk[A]    => Files.deleteIfExists(Paths.get(ef.filename))
        case ef: Slurped[A]   => Files.deleteIfExists(Paths.get(ef.filename))
        case ef: Transient[A] => ef.entries match {
          case it: EIterator[A] => it.close()
          case _ => { }
        }
        case _              => { }
      }
    } catch { 
        // Cannot delete a file.
        // Probably it is opened by another process.
        // Just ignore it. 
        case _: Throwable => { }
    }



  /** Save the EFile to a file with a new name.
   *
   *  May need to recursively save its elements; this is done by
   *  iterating the specified preparations on these elements.
   *
   *  @param name   is the new name.
   *  @param folder is the folder to save to.
   *  @return the EFile with the new name. 
   */
      
    def savedAs(name: String, folder: String = ""): EFile[A] = {

      val newname =  {
        val pfix = if (folder == "") "" else folder + "/" 
        val sfix = if (name endsWith settings.suffixsav) ""
                   else settings.suffixsav
        pfix + name + sfix
      }

      val saved = serialized(folder)

      Files.move(
        Paths.get(saved.filename),
        Paths.get(newname),
        StandardCopyOption.REPLACE_EXISTING)
      OnDisk[A](newname, settings).toEFile
    }


  /** @return this EFile as a vector
   */

    def toVector: Vector[A] = eiterator.toVector



  /** Merge this EFile with other EFiles, assuming elements on
   *  all the EFiles are already sorted in the same ordering.
   *
   *  @param that* are the other EFiles.
   *  @return the merged EFile.
   */

    def mergedWith(that: EFile[A]*): EFile[A] = {
      val efobjs = this +: (that.toVector)
      EFile.merge[A](efobjs :_*)(cmp, onDisk = false)
    }


  /** Sort this EFile.
   *
   * @param cmp is the ordering on elements.
   * @param capacity is the # of elements to keep in memory during the sort.
   * @param onDisk indicates whether to always sort on disk, even small files.
   * @return the sorted EFile.
   */

    def sortedWith(
      cmp:      (A, A) => Boolean,
      capacity: Int     = efile.settings.cap,
      onDisk:   Boolean = false): 
    EFile[A] = {

      EFile.sortWith(this)(cmp, capacity, onDisk)

    }



  /** Sort this EFile.
   *
   * @param cmp is the ordering on elements.
   * @param capacity is the # of elements to keep in memory during the sort.
   * @param onDisk indicates whether to always sort on disk, even small files.
   * @return the sorted EFile.
   */

    def sorted: EFile[A] = EFile.sort[A](this)(cmp, cap, onDisk = false)



  /** Sort this EFile if necessary
   *
   * @param cmp is the ordering on elements.
   * @param capacity is the # of elements to keep in memory during the sort.
   * @param onDisk indicates whether to always sort on disk, even small files.
   * @return the sorted EFile.
   */

    def sortedIfNeeded: EFile[A] = 
      EFile.sortIfNeeded[A](this)(cmp, cap, onDisk = false) 


  /** Check whether this EFile is sorted.
   *
   *  @param cmp is the ordering on elements.
   *  @return whether this EFile is sorted.
   */

    def isSorted: Boolean = EFile.isSorted[A](this)(cmp)


  /** Check whether this EFile has the same value as another EFile,
   *  based on a given equality test on elements.
   *
   *  @param that   is the other EFile
   *  @param forced if == true, compare the EFiles even when they are transient
   *  @param equals is the equality test on elements
   *  @return whether the two EFiles have the same value.
   */

    def hasSameValueAs
      (that: EFile[A])
      (implicit 
         forced: Boolean = true,
         sameElem: (A, A) => Boolean = (x: A, y: A) => x == y)
    : Boolean = {

      EFile.hasSameValueAs(this, that)(forced, sameElem)

    }



  /** Return whether an EFile is in transient state.
   */

    def isTransient: Boolean = efile match {
      case ef: Transient[A] => true
      case _ => false
    }

  }  // End class EFile




  /** Constructors for EFiles and mmplementation of various EFile methods.
   */
 
  object EFile {

  /** @return default settings for EFiles.
   */

    def setDefaultsEFile[A]
      (prefix: String        = "synchrony-",
       suffixtmp: String     = ".eftmp",
       suffixsav: String     = ".efsav",
       aveSz: Int            = 1000,
       cardCap: Int          = 2000,
       ramCap: Long          = 200000000L,
       cap: Int              = 100000,   
       doSampling: Boolean   = true,
       samplingSz: Int       = 30,
       alwaysOnDisk: Boolean = false,
       totalsizeOnDisk: EFile[A]=>Double = (x:EFile[A]) => x.filesize.toDouble,
       serializer: Serializer[A] = serializerEFile[A](new ItemFormatter[A]),
       deserializer: Deserializer[A] = deserializerEFile[A](new ItemParser[A]))
      (implicit ct: ClassTag[A], cmp: Ordering[A])
    : EFileSettings[A] = {

      EFileSettings[A](
        prefix          = prefix,
        suffixtmp       = suffixtmp,
        suffixsav       = suffixsav,
        aveSz           = aveSz,
        cardCap         = cardCap,
        ramCap          = ramCap,
        cap             = cap,
        doSampling      = doSampling,
        samplingSz      = samplingSz,
        alwaysOnDisk    = alwaysOnDisk,
        totalsizeOnDisk = totalsizeOnDisk,
        serializer      = serializer,
        deserializer    = deserializer,
        cmp             = cmp,
        classTag        = ct
      ) 

    }



  /** Construct an EFile with a given EFileState.
   *
   *  @param efile is the EFileState.
   *  @return the constructed EFile.
   */

    def apply[A](efile:EFileState[A]):EFile[A] = new AnEFile[A](efile)


  /** Construct an in-memory EFile.
   *
   *  @param entries are the entries of the EFile.
   *  @param settings are the settings of the EFile.
   *  @return the constructed EFile.
   */

    def inMemoryEFile[A]
      (entries: Vector[A])
      (implicit settings: EFileSettings[A])
    : EFile[A] = {

      new AnEFile[A](InMemory(entries, settings))

    }
    


  /** Construct a slurped EFile.
   *
   *  @param raw is a String representing the EFile.
   *  @return the constructed EFile.
   */

    def slurpedEFile[A]
      (raw: String, filename: String)
      (implicit settings: EFileSettings[A])
    : EFile[A] = {

      new AnEFile[A](Slurped[A](raw, filename, settings))

    }



  /** Construct a transient EFile.
   *
   *  @param entries are the entries of the EFile.
   *  @param settings are the settings of the EFile.
   *  @return the constructed EFile.
   */

    def transientEFile[A]
      (entries: Iterator[A])
      (implicit settings: EFileSettings[A])
    : EFile[A] = {

      new AnEFile[A](Transient(entries, settings))

    }



  /** Construct an on-disk EFile.
   *
   *  @param filename is name of the EFile on disk.
   *  @param settings gives the settings of the EFile.
   *  @param nocheck  indicates whether to skip the "isRegularFile" check.
   *                  This check is inefficient. So skip when you can!
   *  @return the constructed EFile.
   */

    def onDiskEFile[A]
      (filename: String, nocheck : Boolean = false)
      (implicit settings: EFileSettings[A])
    : EFile[A] = {

      def isFile(f:String) = nocheck || {
          try Files.isRegularFile(Paths.get(f))
          catch { case _: Throwable => false }
      }

      val tmpf = filename + settings.suffixtmp
      val savf = filename + settings.suffixsav
      val f = if (isFile(filename)) filename
              else if (isFile(savf)) savf
              else if (isFile(tmpf)) tmpf
              else { throw FileNotFound("") }

      val efobj = new AnEFile[A](OnDisk(f, settings))

      AUTOSLURP match {
        case true  => efobj.slurped
        case false => efobj
      }

    }



  /** Default serializer.
   *
   * @param itemFormatter is function for formatting an item.
   * @return a serializer according to the provided format.
   */

    def serializerEFile[A](itemFormatter: Formatter[A]): Serializer[A] = {

      new FileSerializer(itemFormatter)

    }




  /** Default deserializer.
   *
   *  @param itemParser is parser for reading an item.
   *  @param guard tests whether a string/line should be parsed.
   *  @return a deserializer 
   */

    def deserializerEFile[A]
      (itemParser: Parser[A],
       guard: String => Boolean = (x: String) => true):
    Deserializer[A] = { 

      new FileDeserializer(itemParser, guard)

    }



  /** Merging EFiles.
   *
   *  @param efobjs are the EFiles to merge. Their elements are assumed
   *     to be already correctly ordered/sorted.
   *  @param onDisk is set to true to force pure merging on disk.
   *  @param cmp is the ordering on elements.
   *  @return the merged EFile.
   */

    def merge[A]
      (efobjs: EFile[A]*)
      (implicit cmp: Ordering[A], onDisk: Boolean = false)
    : EFile[A] = {

      if (efobjs.length == 0) {
        if (DEBUG) println("**There must be at least one EFile for merging.")
        throw FileNotFound("There must be at least one EFile for merging.")
      }

      type EI = EIterator[A]

      val ordering = new Ordering[EI] {
        override def compare(x:EI, y:EI) = cmp.compare(x.head, y.head) 
      }

      def bs(efs: Vector[EI], a: A) = {
        var lb    = 0
        var ub    = efs.length - 1
        var i     = (ub + lb) / 2
        while (lb <= i && i <= ub) {
          scala.math.signum(cmp.compare(a, efs(i).head)) match {
             case  1 => { lb = i + 1 }
             case  0 => { lb = i + 1 }
             case -1 => { ub = i - 1 }
          }
          i = (ub + lb) / 2
        }
        lb
      }

      val settings = efobjs(0).efile.settings

      var efs: Vector[EI] =
        efobjs
        .toVector
        .map(ef => if (onDisk) ef.serialized else ef.stored) 
        .map(ef => ef.eiterator)
        .filter(ei => (ei.hasNext || { ei.close(); false }))
        .sorted(ordering)

      val it = new Iterator[A] {

        override def hasNext = (efs.length > 0) && (efs(0).hasNext)

        override def next() = {

          val here = efs(0)
          val curr = here.next()
          val rest = efs.drop(1)
 
          efs = here.hasNext match {
            case true =>
              val i = bs(rest, here.head)
              val (before, after) = rest.splitAt(i)
              before ++ Vector(here) ++ after
            case false => 
              here.close()
              rest
          }

          curr
        }
      }

      if (onDisk) Transient(it, settings).toEFile.serialized
      else Transient(it, settings).toEFile
    }



  /** Sort an EFile.
   *
   *  @param efobj is the EFile to sort.
   *  @param cmp is the ordering on elements.
   *  @param capacity is the # of elements in memory during sorting.
   *  @param onDisk is set to true to force sorting on disk.
   *  @return the sorted EFile.
   */

    def sortWith[A]
      (efobj: EFile[A])
      (cmp: (A, A) => Boolean,
       capacity: Int = efobj.efile.settings.cap,
       onDisk: Boolean = false)
    : EFile[A] = {

      val ordering = new Ordering[A] {
        override def compare(x:A, y:A) = (cmp(x,y), cmp(y,x)) match {
          case (true, false) => -1
          case (false, true) => 1
          case _ => 0
        }
      }

      sort[A](efobj)(cmp=ordering, capacity=capacity, onDisk=onDisk)
    }



  /** Sort an EFile.
   *
   *  @param efobj is the EFile to sort.
   *  @param cmp is the ordering on elements.
   *  @param capacity is the # of elements in memory during sorting.
   *  @param onDisk is set to true to force sorting on disk.
   *  @return the sorted EFile.
   */

    def sort[A]
      (efobj: EFile[A])
      (implicit 
         cmp: Ordering[A],
         capacity: Int = efobj.efile.settings.cap,
         onDisk: Boolean = false): 
    EFile[A] = efobj.isEmpty match {

      case false =>     // EFile is non-empty. Sort it.
   
        val alwaysOnDisk = efobj.efile match { 
          case ef:OnDisk[A] => true
          case _ => onDisk
        }

        val it = efobj.eiterator
      
        val settings = efobj.efile.settings

        val estimatedCap =
          if (!settings.doSampling || (capacity != settings.cap)) capacity
          else { 
            val sampling = it.lookahead(settings.samplingSz)
            val tmp = for (s <- sampling) yield ItemFormatter.format[A](s)
            val sz = tmp.map(_.length).reduce(_ + _)
            val aveSz = sz / (sampling.length max 1)
            val cap = (settings.ramCap / (aveSz max 1)).toInt 
           
            if (DEBUG) { 
              println(s"*** cap = ${cap}, sz = ${sz}," +
                      s" aveSz = ${aveSz}, n = ${sampling.length}")
            }
            cap
          }

        var m = 0
  
        val groups = {
          for(g <- it.grouped(estimatedCap))
          yield {
            m = m + 1
            if (DEBUG) println(s"*** splitting, m = ${m}") 
            val gsorted = Transient(g.sorted(cmp).iterator, settings).toEFile
            if (alwaysOnDisk) gsorted.serialized
            else gsorted.stored
          }
        }.toVector

        try merge[A](groups :_*)(onDisk=alwaysOnDisk, cmp=cmp).serialized
        finally { it.close(); groups.foreach{ _.destruct() } }

      case true  => efobj    // EFile is empty. No need to sort.

    }



  /** Sort only if necessary.
   *
   *  @param efobj is the EFile to sort.
   *  @param cmp is the ordering on elements.
   *  @param capacity is the # of elements in memory during sorting
   *  @param onDisk is set to true to force sorting on disk
   *  @return the sorted EFile.
   */

    def sortIfNeeded[A]
      (efobj: EFile[A])
      (implicit
         cmp: Ordering[A],
         capacity: Int = efobj.efile.settings.cap,
         onDisk: Boolean = false)
    : EFile[A] = { 
      val storedEfobj = efobj.stored
      isSorted[A](storedEfobj)(cmp) match {
        case true  => storedEfobj
        case false => sort(storedEfobj)(cmp, capacity, onDisk)
      }
    }



  /** Check whether this EFile is sorted.
   *
   *  @param efobj is the Efile
   *  @param cmp is the ordering on elements.
   *  @return whether this EFile is sorted.
   */

    def isSorted[A](efobj: EFile[A])(implicit cmp: Ordering[A]): Boolean = {
      val it = efobj.stored.eiterator
      it.peekahead(1) match {
        case None    => true
        case Some(u) =>
          var sorted = true
          var curr   = it.next()
          while (sorted && it.hasNext) {
            val tmp = it.next()
            sorted = cmp.lteq(curr, tmp)
            curr = tmp
         }
         sorted
       }
    }



  /** Check whether this EFile has the same value as another EFile,
   *  based on a given equality test on elements.
   *
   *  @param efobjx   
   *  @param efobjy are EFiles
   *  @param forced if == true, compare the EFiles even when they are transient
   *  @param equals is the equality test on elements
   *  @return whether the two EFiles have the same value.
   */

    def hasSameValueAs[A]
      (efobjx: EFile[A], efobjy: EFile[A])
      (implicit 
         forced: Boolean = true,
         sameElem: (A, A) => Boolean = (x: A, y: A) => x == y)
    : Boolean = {

      def sameVal(x: EFile[A], y: EFile[A]) = {
        var acc = true
        val xit = x.eiterator
        val yit = y.eiterator
        while (acc && xit.hasNext && yit.hasNext) {
          acc = sameElem(xit.next(), yit.next())
        }
        try { acc && !xit.hasNext && !yit.hasNext }
        finally { xit.close(); yit.close() }
      }

      def sameFile(x: EFile[A], y: EFile[A]) = {
        x.filename != "" &&
        x.filename == y.filename 
      }

      def same(x: EFile[A], y: EFile[A]) = sameFile(x, y) || sameVal(x, y)

      (forced, efobjx.isTransient, efobjy.isTransient) match {
        case (true, _, _)          => same(efobjx, efobjy)
        case (false, false, false) => same(efobjx, efobjy)
        case _ => false
      }
    }

  } // End object EFile.



  /**
   *  Make implicit conversion to endow EIterators
   *  with aggregate functions.
   */

  object implicits {

    import scala.language.implicitConversions
    import synchrony.iterators.AggrCollections._

    implicit def EFile2EIter[A](efobj: EFile[A]): EIterator[A] =
      efobj.eiterator

    implicit def EFileLiftInMemory[A](ef: InMemory[A]) = ef.toEFile

    implicit def EFileLiftTransient[A](ef: Transient[A]) = ef.toEFile

    implicit def EFileLiftOnDisk[A](ef: OnDisk[A]) = ef.toEFile

    implicit def EIter2AggrIter[A](it: EIterator[A]): AggrIterator[A] =
      AggrIterator[A](it)

    implicit def Vec2AggrIter[A](it: Vector[A]): AggrIterator[A] =
      AggrIterator[A](it)

    implicit def Iter2AggrIter[A](it: Iterator[A]): AggrIterator[A] =
      AggrIterator[A](it)

  }



  /** A generic demo class for serialization/deserialization.
   */

  object Demo {

    case class Entry[A](e:A)

    object Entry {
    
      def cmp[A](implicit ord:Ordering[A]): Ordering[Entry[A]] =
        Ordering.by((x:Entry[A]) => x.e)

      def settings[A]
        (implicit ct: ClassTag[Entry[A]], cmp: Ordering[Entry[A]])
      : EFileSettings[Entry[A]] = {

        EFile.setDefaultsEFile[Entry[A]]()(ct, cmp) 

      }
    }
  }

} // End object FileCollections




/** Examples
 *

  {{{


import synchrony.iterators.FileCollections._
import EFile._
import OpG._
import Demo._
// import synchrony.iterators.FileCollections.implicits._


val a = Entry(10)
val b = Entry(20)
val c = Entry(30)
val ABC = Vector(a,c,b,c,b,a,a,b,c,a)

val ef = EFile.inMemoryEFile(ABC)

val gh = ef.sorted(cmp = Ordering.by((x:Entry[Int]) => x.e))

val ij = gh.serialized

ij.filesize

ij.eiterator.toVector

ij.savedAs("xy")

val mn = EFile.onDiskEFile[Entry[Int]]("xy")

mn.eiterator.toVector

mn.eiterator.length

mn.flatAggregateBy(count)

mn.slurped

mn.slurped.slurped

mn.slurped.slurped(1)

mn.slurped.slurped.flatAggregateBy(count)

onDiskEFile[Entry[Int]]("xy").sorted


    }}}

 *
 */


