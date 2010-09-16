package com.jetbrains.python.psi.resolve;

import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.HashSet;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyImportResolver;
import com.jetbrains.python.psi.impl.PyQualifiedName;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author dcheryasov
 */
public class ResolveImportUtil {
  private ResolveImportUtil() {
  }

  private static final ThreadLocal<Set<String>> ourBeingImported = new ThreadLocal<Set<String>>() {
    @Override
    protected Set<String> initialValue() {
      return new HashSet<String>();
    }
  };

  public static boolean isAbsoluteImportEnabledFor(PsiElement foothold) {
    if (foothold != null) {
      PsiFile file = foothold.getContainingFile();
      if (file instanceof PyFile) {
        final PyFile pyFile = (PyFile)file;
        if (pyFile.getLanguageLevel().isPy3K()) {
          return true;
        }
        return pyFile.isAbsoluteImportEnabled();
      }
    }
    // if the relevant import is below the foothold, it is either legal or we've detected the offending statement already
    return false;
  }


  /**
   * Finds a directory that many levels above a given file, making sure that every level has an __init__.py.
   *
   * @param base  file that works as a reference.
   * @param depth must be positive, 1 means the dir that contains base, 2 is one dir above, etc.
   * @return found directory, or null.
   */
  @Nullable
  public static PsiDirectory stepBackFrom(PsiFile base, int depth) {
    if (depth == 0) {
      return base.getContainingDirectory();
    }
    PsiDirectory result;
    if (base != null) {
      base = base.getOriginalFile(); // just to make sure 
      result = base.getContainingDirectory();
      int count = 1;
      while (result != null && result.findFile(PyNames.INIT_DOT_PY) != null) {
        if (count >= depth) return result;
        result = result.getParentDirectory();
        count += 1;
      }
    }
    return null;
  }

  @Nullable
  public static PsiElement resolveImportElement(PyImportElement import_element) {
    return resolveImportElement(import_element, import_element.getImportedQName());
  }

  @Nullable
  public static PsiElement resolveImportElement(PyImportElement import_element, final PyQualifiedName qName) {
    final List<PsiElement> psiElements = multiResolveImportElement(import_element, qName);
    if (psiElements.size() > 1) {
      // prefer the directory which has a non-empty __init__.py
      for (PsiElement element : psiElements) {
        final PsiElement init = PyUtil.turnDirIntoInit(element);
        if (init instanceof PsiFile) {
          VirtualFile vFile = ((PsiFile) init).getVirtualFile();
          if (vFile != null && vFile.getLength() > 0) {
            return element;
          }
        }
      }
    }
    return psiElements.isEmpty() ? null : psiElements.get(0);
  }

  @NotNull
  public static List<PsiElement> multiResolveImportElement(PyImportElement import_element, final PyQualifiedName qName) {
    if (qName == null) {
      return Collections.emptyList();
    }

    final PsiFile file = import_element.getContainingFile().getOriginalFile();
    final PyStatement importStatement = import_element.getContainingImportStatement();

    boolean absolute_import_enabled = isAbsoluteImportEnabledFor(import_element);
    PyQualifiedName moduleQName = null;

    if (importStatement instanceof PyFromImportStatement) {
      PyFromImportStatement from_import_statement = (PyFromImportStatement)importStatement;
      moduleQName = from_import_statement.getImportSourceQName();
      final int relative_level = from_import_statement.getRelativeLevel();

      if (relative_level > 0 && moduleQName == null) { // "from ... import foo"
        final PsiElement element = resolveChild(stepBackFrom(file, relative_level), qName.getComponents().get(0), file, null, false);
        return element != null ? Collections.singletonList(element) : Collections.<PsiElement>emptyList();
      }

      if (moduleQName != null) { // either "from bar import foo" or "from ...bar import foo"
        final List<PsiElement> candidates = resolveModule(moduleQName, file, absolute_import_enabled, relative_level);
        for (PsiElement candidate : candidates) {
          PsiElement result = resolveChild(candidate, qName.getComponents().get(0), file, null, false);
          if (result != null) return Collections.singletonList(result);
        }
      }
    }
    else if (importStatement instanceof PyImportStatement) { // "import foo"
      return resolveModule(qName, file, absolute_import_enabled, 0);
    }
    // in-python resolution failed
    if (moduleQName != null) {
      final List<PsiElement> importFrom = resolveModule(moduleQName, file, false, 0);
      final PsiElement result = resolveForeignImport(import_element, StringUtil.join(qName.getComponents(), "."),
                                                     importFrom.isEmpty() ? null : importFrom.get(0));
      return result != null ? Collections.singletonList(result) : Collections.<PsiElement>emptyList();
    }
    return Collections.emptyList();
  }

  @NotNull
  public static List<PsiElement> resolveImportReference(final PyReferenceExpression importRef) {
    // prerequisites
    if (importRef == null) return Collections.emptyList();
    if (!importRef.isValid()) return Collections.emptyList(); // we often catch a reparse while in a process of resolution
    final String referencedName = importRef.getReferencedName(); // it will be the "foo" in later comments
    if (referencedName == null) return Collections.emptyList();
    final PsiFile file = importRef.getContainingFile();
    if (file == null || !file.isValid()) return Collections.emptyList();

    final PsiElement parent =
      PsiTreeUtil.getParentOfType(importRef, PyImportElement.class, PyFromImportStatement.class); //importRef.getParent();
    if (parent instanceof PyImportElement) {
      PyImportElement import_element = (PyImportElement)parent;
      return multiResolveImportElement(import_element, importRef.asQualifiedName());
    }
    else if (parent instanceof PyFromImportStatement) { // "from foo import"
      PyFromImportStatement from_import_statement = (PyFromImportStatement)parent;
      return resolveFromImportStatementSource(from_import_statement, importRef.asQualifiedName());
    }
    return Collections.emptyList();
  }

  @Nullable
  public static PsiElement resolveFromImportStatementSource(PyFromImportStatement from_import_statement) {
    final PyQualifiedName qName = from_import_statement.getImportSourceQName();
    if (qName == null) {
      return null;
    }
    final List<PsiElement> source = resolveFromImportStatementSource(from_import_statement, qName);
    return source.isEmpty() ? null : source.get(0);
  }

  @NotNull
  private static List<PsiElement> resolveFromImportStatementSource(PyFromImportStatement from_import_statement, PyQualifiedName qName) {
    boolean absolute_import_enabled = isAbsoluteImportEnabledFor(from_import_statement);
    PsiFile file = from_import_statement.getContainingFile();
    return resolveModule(qName, file, absolute_import_enabled, from_import_statement.getRelativeLevel());
  }

  /**
   * Resolves a module reference in a general case.
   *
   * @param qualifiedName      qualified name of the module reference to resolve
   * @param source_file        where that reference resides; serves as PSI foothold to determine module, project, etc.
   * @param import_is_absolute if false, try old python 2.x's "relative first, absolute next" approach.
   * @param relative_level     if > 0, step back from source_file and resolve from there (even if import_is_absolute is false!).
   * @return list of possible candidates
   */
  @NotNull
  public static List<PsiElement> resolveModule(@Nullable PyQualifiedName qualifiedName, PsiFile source_file,
                                               boolean import_is_absolute, int relative_level) {
    if (qualifiedName == null) return Collections.emptyList();
    String marker = StringUtil.join(qualifiedName.getComponents(), ".") + "#" + Integer.toString(relative_level);
    Set<String> being_imported = ourBeingImported.get();
    if (being_imported.contains(marker)) return Collections.emptyList(); // break endless loop in import
    try {
      being_imported.add(marker);
      if (relative_level > 0) {
        // "from ...module import"
        final PsiElement module = resolveModuleAt(stepBackFrom(source_file, relative_level), source_file, qualifiedName);
        return module != null ? Collections.singletonList(module) : Collections.<PsiElement>emptyList();
      }
      else { // "from module import"
        if (import_is_absolute) {
          return resolveModulesInRoots(qualifiedName, source_file);
        }
        else {
          PsiElement module = resolveModuleAt(source_file.getOriginalFile().getContainingDirectory(), source_file, qualifiedName);
          if (module != null) {
            return Collections.singletonList(module);
          }
          return resolveModulesInRoots(qualifiedName, source_file);
        }
      }
    }
    finally {
      being_imported.remove(marker);
    }
  }

  /**
   * Searches for a module at given directory, unwinding qualifiers and traversing directories as needed.
   *
   * @param directory     where to start from; top qualifier will be searched for here.
   * @param sourceFile    the file containing the import statement being resolved
   * @param qualifiedName the qualified name of the module to search
   * @return module's file, or null.
   */
  @Nullable
  private static PsiElement resolveModuleAt(PsiDirectory directory, PsiFile sourceFile, PyQualifiedName qualifiedName) {
    // prerequisites
    if (directory == null || !directory.isValid()) return null;
    if (sourceFile == null || !sourceFile.isValid()) return null;

    PsiElement seeker = directory;
    for (String name : qualifiedName.getComponents()) {
      if (name == null) {
        return null;
      }
      seeker = resolveChild(seeker, name, sourceFile, null, true);
    }
    return seeker;
  }

  @Nullable
  public static PsiElement resolveModuleInRoots(@NotNull PyQualifiedName moduleQualifiedName, PsiElement foothold) {
    final List<PsiElement> candidates = resolveModulesInRoots(moduleQualifiedName, foothold);
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  /**
   * Returns the list of directories/files under different project roots which match the specified qualified name.
   *
   * @param moduleQualifiedName the qualified name to find
   * @param foothold            the PSI element in the context of which the search is performed
   * @return the list of matching directories or files, or an empty list if nothing was found
   */
  @NotNull
  public static List<PsiElement> resolveModulesInRoots(@NotNull PyQualifiedName moduleQualifiedName, PsiElement foothold) {
    if (foothold == null || !foothold.isValid()) return Collections.emptyList();
    PsiFile footholdFile = foothold.getContainingFile();
    if (footholdFile == null || !footholdFile.isValid()) return Collections.emptyList();

    PythonPathCache cache = null;
    final Module module = ModuleUtil.findModuleForPsiElement(foothold);
    if (module != null) {
      cache = PythonPathCache.getInstance(module);
    }
    else {
      final Sdk sdk = PyBuiltinCache.findSdkForFile(footholdFile);
      if (sdk != null) {
        cache = PythonPathCache.getInstance(foothold.getProject(), sdk);
      }
    }
    if (cache != null) {
      final List<PsiElement> cachedResults = cache.get(moduleQualifiedName);
      if (cachedResults != null) {
        return cachedResults;
      }
    }
    ResolveInRootVisitor visitor = new ResolveInRootVisitor(moduleQualifiedName, foothold.getManager(), footholdFile);
    visitRoots(foothold, visitor);
    if (cache != null) {
      cache.put(moduleQualifiedName, visitor.results);
    }
    return visitor.results;
  }

  // TODO: rewrite using resolveImportReference

  /**
   * Resolves either <tt>import foo</tt> or <tt>from foo import bar</tt>.
   *
   * @param importRef      refers to the name of the module being imported (the <tt>foo</tt>).
   * @param referencedName the name imported from the module (the <tt>bar</tt> in <tt>import from</tt>), or null (for just <tt>import foo</tt>).
   * @return element the name resolves to, or null.
   */
  @Nullable
  public static PsiElement resolvePythonImport2(final PyReferenceExpression importRef, final String referencedName) {
    if (!importRef.isValid()) return null; // we often catch a reparse while in a process of resolution
    final String the_name = referencedName != null ? referencedName : importRef.getName();
    Set<String> being_imported = ourBeingImported.get();
    PsiFile containing_file = importRef.getContainingFile();
    PsiElement last_resolved;
    List<PyReferenceExpression> ref_path = PyResolveUtil.unwindQualifiers(importRef);
    if (ref_path == null) return null;
    // join path to form the FQN: (a, b, c) -> a.b.c.
    StringBuffer pathbuf = new StringBuffer();
    for (PyQualifiedExpression pathelt : ref_path) pathbuf.append(pathelt.getName()).append(".");
    if (referencedName != null) pathbuf.append(referencedName);
    final String import_fqname = pathbuf.toString();
    if (being_imported.contains(import_fqname)) return null; // already trying this path, unable to recurse
    try {
      being_imported.add(import_fqname); // mark
      // resolve qualifiers
      Iterator<PyReferenceExpression> it = ref_path.iterator();
      if (ref_path.size() > 1) { // it was a qualified name
        if (it.hasNext()) {
          last_resolved = it.next().getReference().resolve(); // our topmost qualifier, not ourselves for certain
        }
        else {
          return null;
        } // topmost qualifier not found
        while (it.hasNext()) {
          final String name = it.next().getName();
          if (name == null) {
            return null;
          }
          last_resolved = resolveChild(last_resolved, name, containing_file, null, true);
          if (last_resolved == null) return null; // anything in the chain unresolved means that the whole chain fails
        }
        if (referencedName != null) {
          return resolveChild(last_resolved, referencedName, containing_file, null, false);
        }
        else {
          return last_resolved;
        }
      }

      // non-qualified name
      if (referencedName != null) {
        return resolveChild(importRef.getReference().resolve(), referencedName, containing_file, null, false);
        // the importRef.resolve() does not recurse infinitely because we're asked to resolve referencedName, not importRef itself
      }
      // unqualified import can be found:
      // in the same dir
      PsiElement root_elt = resolveInRoots(importRef, the_name);
      if (root_elt != null) return root_elt;
    }
    finally {
      being_imported.remove(import_fqname); // unmark
    }
    return null; // not resolved by any means
  }


  public static void visitRoots(final PsiElement elt, @NotNull final RootVisitor visitor) {
    // real search
    final Module module = ModuleUtil.findModuleForPsiElement(elt);
    if (module != null) {
      visitRoots(module, visitor);
    }
    else {
      visitSdkRoots(elt, visitor);

    }
  }

  private static void visitSdkRoots(PsiElement elt, RootVisitor visitor) {
    // no module, another way to look in SDK roots
    final PsiFile elt_psifile = elt.getContainingFile();
    if (elt_psifile != null) {  // formality
      final VirtualFile elt_vfile = elt_psifile.getVirtualFile();
      if (elt_vfile != null) { // reality
        for (OrderEntry entry : ProjectRootManager.getInstance(elt.getProject()).getFileIndex().getOrderEntriesForFile(elt_vfile)) {
          if (!visitOrderEntryRoots(visitor, entry)) break;
        }
      }
    }
  }

  private static void visitRoots(Module module, RootVisitor visitor) {
    // TODO: implement a proper module-like approach in PyCharm for "project's dirs on pythonpath", minding proper search order
    // Module-based approach works only in the IDEA plugin.
    ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
    // look in module sources
    boolean sourceEntriesMissing = true;
    Set<VirtualFile> contentRoots = new java.util.HashSet<VirtualFile>();
    for (ContentEntry entry : rootManager.getContentEntries()) {
      VirtualFile rootFile = entry.getFile();

      if (rootFile != null && !visitor.visitRoot(rootFile)) return;
      contentRoots.add(rootFile);
      for (VirtualFile folder : entry.getSourceFolderFiles()) {
        sourceEntriesMissing = false;
        if (!visitor.visitRoot(folder)) return;
      }
    }
    if (sourceEntriesMissing) {
      // fallback for a case without any source entries: use project root
      VirtualFile projectRoot = module.getProject().getBaseDir();
      if (projectRoot != null && !contentRoots.contains(projectRoot) && !visitor.visitRoot(projectRoot)) return;
    }
    // else look in SDK roots
    rootManager.orderEntries().process(new SdkRootVisitingPolicy(visitor), null);
  }

  private static boolean visitOrderEntryRoots(RootVisitor visitor, OrderEntry entry) {
    Set<VirtualFile> allRoots = new LinkedHashSet<VirtualFile>();
    Collections.addAll(allRoots, entry.getFiles(OrderRootType.SOURCES));
    Collections.addAll(allRoots, entry.getFiles(OrderRootType.CLASSES));
    for (VirtualFile root : allRoots) {
      if (!visitor.visitRoot(root)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Looks for a name among element's module's roots; if there's no module, then among project's roots.
   *
   * @param elt     PSI element that defines the module and/or the project.
   * @param refName module name to be found among roots.
   * @return a PsiFile, a child of a root.
   */
  @Nullable
  public static PsiElement resolveInRoots(@NotNull final PsiElement elt, final String refName) {
    // NOTE: a quick and dirty temporary fix for "current dir" root path, which is assumed to be present first (but may be not).
    PsiElement res = resolveInCurrentDir(elt, refName);
    if (res != null) {
      return res;
    }
    else {
      return resolveModuleInRoots(PyQualifiedName.fromDottedString(refName), elt);
    }
  }

  @Nullable
  public static PsiElement resolveInCurrentDir(@NotNull final PsiElement elt, final String refName) {
    PsiFile pfile = elt.getContainingFile();
    VirtualFile vfile = pfile.getVirtualFile();
    if (vfile == null) { // we're probably within a copy, e.g. for completion; get the real thing
      pfile = pfile.getOriginalFile();
    }
    if (pfile != null) {
      PsiDirectory pdir = pfile.getContainingDirectory();
      if (pdir != null) {
        PsiElement child_elt = resolveChild(pdir, refName, pfile, null, true);
        if (child_elt != null) return child_elt;
      }
    }
    return null;
  }

  @Nullable
  private static PsiElement resolveForeignImport(final PyElement importElement, final String importText, final PsiElement importFrom) {
    for (PyImportResolver resolver : Extensions.getExtensions(PyImportResolver.EP_NAME)) {
      PsiElement result = resolver.resolveImportReference(importElement, importText, importFrom);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  static class ResolveInRootVisitor implements RootVisitor {
    final PsiFile foothold_file;
    final @NotNull PyQualifiedName qualifiedName;
    final @NotNull PsiManager psiManager;
    final List<PsiElement> results = new ArrayList<PsiElement>();

    public ResolveInRootVisitor(@NotNull PyQualifiedName qName, @NotNull PsiManager psiManager, PsiFile foothold_file) {
      this.qualifiedName = qName;
      this.psiManager = psiManager;
      this.foothold_file = foothold_file;
    }

    public boolean visitRoot(final VirtualFile root) {
      if (!root.isValid()) {
        return true;
      }
      PsiElement module = resolveInRoot(root, qualifiedName, psiManager, foothold_file);
      if (module != null) {
        results.add(module);
      }
      return true;
    }
  }

  @Nullable
  private static PsiElement resolveInRoot(VirtualFile root, PyQualifiedName qualifiedName, PsiManager psiManager, PsiFile foothold_file) {
    PsiElement module = root.isDirectory() ? psiManager.findDirectory(root) : psiManager.findFile(root);
    for (String component : qualifiedName.getComponents()) {
      if (component == null) {
        module = null;
        break;
      }
      module = resolveChild(module, component, foothold_file, root, false); // only files, we want a module
    }
    return module;
  }

  /**
   * Tries to find referencedName under the parent element. Used to resolve any names that look imported.
   * Parent might happen to be a PyFile(__init__.py), then it is treated <i>both</i> as a file and as ist base dir.
   * For details of this ugly magic, see {@link com.jetbrains.python.psi.impl.PyReferenceExpressionImpl#resolve()}.
   *
   * @param parent         element under which to look for referenced name; if null, null is returned.
   * @param referencedName which name to look for.
   * @param containingFile where we're in.
   * @param root           the root from which we started descending the directory tree (if any)
   * @param fileOnly       if true, considers only a PsiFile child as a valid result; non-file hits are ignored.
   * @return the element the referencedName resolves to, or null.
   * @todo: Honor module's __all__ value.
   * @todo: Honor package's __path__ value (hard).
   */
  @Nullable
  public static PsiElement resolveChild(@Nullable final PsiElement parent, @NotNull final String referencedName,
                                        final PsiFile containingFile, @Nullable VirtualFile root, boolean fileOnly) {
    PsiDirectory dir = null;
    PsiElement ret = null;
    if (parent instanceof PyFile) {
      if (PyNames.INIT_DOT_PY.equals(((PyFile)parent).getName())) {
        // gobject does weird things like '_gobject = sys.modules['gobject._gobject'], so it's preferable to look at
        // files before looking at names exported from __init__.py
        dir = ((PyFile)parent).getContainingDirectory();
        final PsiElement result = resolveInDirectory(referencedName, containingFile, dir, root, fileOnly);
        if (result != null) {
          return result;
        }
      }
      ret = ((PyFile)parent).getElementNamed(referencedName);
      if (ret != null) return ret;
    }
    else if (parent instanceof PsiDirectory) {
      dir = (PsiDirectory)parent;
    }
    else if (parent instanceof PsiDirectoryContainer) {
      final PsiDirectoryContainer container = (PsiDirectoryContainer)parent;
      for (PsiDirectory childDir : container.getDirectories()) {
        final PsiElement result = resolveInDirectory(referencedName, containingFile, childDir, root, fileOnly);
        //if (fileOnly && ! (result instanceof PsiFile) && ! (result instanceof PsiDirectory)) return null;
        if (result != null) return result;
      }
    }
    if (dir != null) {
      final PsiElement result = resolveInDirectory(referencedName, containingFile, dir, root, fileOnly);
      //if (fileOnly && ! (result instanceof PsiFile) && ! (result instanceof PsiDirectory)) return null;
      return result;
    }
    return ret;
  }

  @Nullable
  private static PsiElement resolveInDirectory(final String referencedName, final PsiFile containingFile,
                                               final PsiDirectory dir, @Nullable VirtualFile root, boolean isFileOnly) {
    if (referencedName == null) return null;
    final PsiElement module = findModuleInDir(dir, referencedName);
    if (module != null) return module;

    if (isInSdk(dir)) {
      PsiDirectory skeletonDir = findSkeletonDir(dir, root);
      if (skeletonDir != null) {
        final PsiFile skeletonFile = findModuleInDir(skeletonDir, referencedName);
        if (skeletonFile != null) {
          return skeletonFile;
        }
      }
    }

    final PsiDirectory subdir = dir.findSubdirectory(referencedName);
    if (subdir != null && subdir.findFile(PyNames.INIT_DOT_PY) != null) {
      return subdir;
    }
    else if (!isFileOnly) {
      // not a subdir, not a file; could be a name in parent/__init__.py
      final PsiFile initPy = dir.findFile(PyNames.INIT_DOT_PY);
      if (initPy == containingFile) return null; // don't dive into the file we're in
      if (initPy instanceof PyFile) {
        return ((PyFile)initPy).getElementNamed(referencedName);
      }
    }
    return null;
  }

  @Nullable
  private static PsiFile findModuleInDir(PsiDirectory dir, String referencedName) {
    final PsiFile file = dir.findFile(referencedName + PyNames.DOT_PY);
    // findFile() does case-insensitive search, and we need exactly matching case (see PY-381)
    if (file != null && FileUtil.getNameWithoutExtension(file.getName()).equals(referencedName)) {
      return file;
    }
    return null;
  }

  private static boolean isInSdk(PsiDirectory dir) {
    final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(dir.getProject()).getFileIndex();
    final List<OrderEntry> entries = fileIndex.getOrderEntriesForFile(dir.getVirtualFile());
    for (OrderEntry entry : entries) {
      if (entry instanceof JdkOrderEntry) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static PsiDirectory findSkeletonDir(PsiDirectory dir, @Nullable VirtualFile root) {
    String relativeName = null;
    if (root != null) {
      relativeName = VfsUtil.getRelativePath(dir.getVirtualFile(), root, '/');
    }
    else {
      PyQualifiedName relativeQName = findShortestImportableQName(dir, dir.getVirtualFile());
      if (relativeQName != null) {
        relativeName = relativeQName.join("/");
      }
    }
    VirtualFile skeletonsRoot = findSkeletonsRoot(dir);
    if (skeletonsRoot != null && relativeName != null) {
      VirtualFile skeletonsVFile = relativeName.length() == 0 ? skeletonsRoot : skeletonsRoot.findFileByRelativePath(relativeName.replace(".", "/"));
      if (skeletonsVFile != null) {
        return dir.getManager().findDirectory(skeletonsVFile);
      }
    }
    return null;
  }

  @Nullable
  private static VirtualFile findSkeletonsRoot(PsiFileSystemItem fsItem) {
    Sdk sdk = PyBuiltinCache.findSdkForFile(fsItem);
    if (sdk != null) {
      return PythonSdkType.findSkeletonsDir(sdk);
    }
    return null;
  }

  /**
   * Tries to find roots that contain given vfile, and among them the root that contains at the smallest depth.
   */
  private static class PathChoosingVisitor implements RootVisitor {

    private final VirtualFile myVFile;
    private List<String> myResult;

    private PathChoosingVisitor(VirtualFile file) {
      if (!file.isDirectory() && file.getName().equals(PyNames.INIT_DOT_PY)) {
        myVFile = file.getParent();
      }
      else {
        myVFile = file;
      }
    }

    public boolean visitRoot(VirtualFile root) {
      final String relativePath = VfsUtil.getRelativePath(myVFile, root, '/');
      if (relativePath != null) {
        List<String> result = StringUtil.split(relativePath, "/");
        if (myResult == null || result.size() < myResult.size()) {
          if (result.size() > 0) {
            result.set(result.size()-1, FileUtil.getNameWithoutExtension(result.get(result.size()-1)));
          }
          myResult = result;
        }
      }
      return myResult == null || myResult.size() > 0;
    }

    public PyQualifiedName getResult() {
      return PyQualifiedName.fromComponents(myResult);
    }
  }

  /**
   * Looks for a way to import given file.
   *
   * @param foothold an element in the file to import to (maybe the file itself); used to determine module, roots, etc.
   * @param vfile    file which importable name we want to find.
   * @return a possibly qualified name under which the file may be imported, or null. If there's more than one way (overlapping roots),
   *         the name with fewest qualifiers is selected.
   */
  @Nullable
  public static String findShortestImportableName(PsiElement foothold, @NotNull VirtualFile vfile) {
    final PyQualifiedName qName = findShortestImportableQName(foothold, vfile);
    return qName == null ? null : qName.toString();
  }

  @Nullable
  public static PyQualifiedName findShortestImportableQName(PsiElement foothold, @NotNull VirtualFile vfile) {
    PathChoosingVisitor visitor = new PathChoosingVisitor(vfile);
    visitRoots(foothold, visitor);
    return visitor.getResult();
  }

  @Nullable
  public static String findShortestImportableName(Module module, @NotNull VirtualFile vfile) {
    PathChoosingVisitor visitor = new PathChoosingVisitor(vfile);
    visitRoots(module, visitor);
    final PyQualifiedName result = visitor.getResult();
    return result == null ? null : result.toString();
  }

  public static class SdkRootVisitingPolicy extends RootPolicy<PsiElement> {
    private final RootVisitor myVisitor;

    public SdkRootVisitingPolicy(RootVisitor visitor) {
      myVisitor = visitor;
    }

    @Nullable
    public PsiElement visitJdkOrderEntry(final JdkOrderEntry jdkOrderEntry, final PsiElement value) {
      if (value != null) return value;  // for chaining in processOrder()
      visitOrderEntryRoots(myVisitor, jdkOrderEntry);
      return null;
    }

    @Nullable
    @Override
    public PsiElement visitLibraryOrderEntry(LibraryOrderEntry libraryOrderEntry, PsiElement value) {
      if (value != null) return value;  // for chaining in processOrder()
      visitOrderEntryRoots(myVisitor, libraryOrderEntry);
      return null;
    }
  }

  public static enum ROLE_IN_IMPORT {
    /**
     * The reference is not inside an import statement.
     */
    NONE,

    /**
     * The reference is inside import and refers to a module
     */
    AS_MODULE,

    /**
     * The reference is inside import and refers to a name imported from a module
     */
    AS_NAME
  }

  /**
   * @param reference what we test
   * @return the role of reference in enclosing import statement, if any
   */
  public static ROLE_IN_IMPORT getRoleInImport(@NotNull PsiReference reference) {
    PsiElement parent = PsiTreeUtil.getParentOfType(
      reference.getElement(),
      PyImportElement.class, PyFromImportStatement.class
    );
    if (parent instanceof PyFromImportStatement) return ROLE_IN_IMPORT.AS_MODULE; // from foo ...
    if (parent instanceof PyImportElement) {
      PsiElement statement = parent.getParent();
      if (statement instanceof PyImportStatement) {
        return ROLE_IN_IMPORT.AS_MODULE; // import foo,...
      }
      else if (statement instanceof PyFromImportStatement) {
        PyFromImportStatement importer = (PyFromImportStatement)statement; // from ??? import foo
        if (importer.getImportSource() == null && importer.getRelativeLevel() > 0) {
          return ROLE_IN_IMPORT.AS_MODULE; // from . import foo,...
        }
        else {
          return ROLE_IN_IMPORT.AS_NAME;
        } // from bar import foo,...
      }
    }
    return ROLE_IN_IMPORT.NONE;
  }
}
