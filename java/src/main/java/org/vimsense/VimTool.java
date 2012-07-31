package org.vimsense;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.xeustechnologies.jcl.JarClassLoader;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public class VimTool {

  public static final Log LOG = LogFactory.getLog(VimTool.class);

  @JsonAutoDetect
  public static class JarInfo {
    public Map<String, PackageInfo> packages;
    public Map<String, ClassInfo> classes;

    public JarInfo() {
      packages = new HashMap<String, PackageInfo>();
      classes = new HashMap<String, ClassInfo>();
    }

    public void add(JarInfo that) {
      this.add(that, false);
    }

    public void add(JarInfo that, boolean overwrite) {
      for (Map.Entry<String, PackageInfo> p : that.packages.entrySet()) {
        if (this.packages.containsKey(p.getKey())) {
          PackageInfo where = this.packages.get(p.getKey());
          for (String sub_package : p.getValue().subpackages) {
            where.subpackages.add(sub_package);
          }
          for (String klass : p.getValue().classes) {
            where.classes.add(klass);
          }
        } else {
          this.packages.put(p.getKey(), p.getValue());
        }
      }
      for (Map.Entry<String, ClassInfo> p : that.classes.entrySet()) {
        if (this.classes.containsKey(p.getKey())) {
          if (overwrite)
            this.classes.put(p.getKey(), p.getValue());
        } else {
          this.classes.put(p.getKey(), p.getValue());
        }
      }
    }
  }

  @JsonAutoDetect
  public static class PackageInfo {
    public String tag = "PACKAGE";
    public Set<String> subpackages;
    public Set<String> classes;

    public PackageInfo() {
      subpackages = new HashSet<String>();
      classes = new HashSet<String>();
    }

  }

  @JsonAutoDetect
  public static class ClassInfo {
    public int secondPass;
    public Set<String> imports;
    public String source = "";
    public String tag = "CLASSDEF";
    public String flags;
    public String name;
    public String pkg;
    public List<String> impl;
    public List<String> parent;
    public String fqn;

    public List<ConstructorInfo> ctors;
    public List<ConstructorInfo> declared_ctors;

    public List<FieldInfo> fields;
    public List<FieldInfo> declared_fields;

    public List<MethodInfo> methods;
    public List<MethodInfo> declared_methods;

    public List<String> classes;
    public List<String> declared_classes;

    public ClassInfo() {
      secondPass = 0;
      imports = new HashSet<String>();
      impl = new ArrayList<String>();
      parent = new ArrayList<String>();
      ctors = new ArrayList<ConstructorInfo>();
      declared_ctors = new ArrayList<ConstructorInfo>();
      fields = new ArrayList<FieldInfo>();
      declared_fields = new ArrayList<FieldInfo>();
      methods = new ArrayList<MethodInfo>();
      declared_methods = new ArrayList<MethodInfo>();
      classes = new ArrayList<String>();
      declared_classes = new ArrayList<String>();
    }
  }

  @JsonAutoDetect
  public static class ConstructorInfo {
    public int m;
    public List<String> p;
    public String d;
    public int[] pos;

    public ConstructorInfo() {
      p = new ArrayList<String>();
      pos = new int[]{-1, -1};
    }
  }

  @JsonAutoDetect
  public static class FieldInfo {
    public String m;
    public String n;
    public String t;
    public String c;
    public int[] pos;

    public FieldInfo() {
      pos = new int[]{-1, -1};
    }
  }

  @JsonAutoDetect
  public static class MethodInfo {
    public String m;
    public List<String> p = new ArrayList<String>();
    public String n;
    public String c;
    public String r;
    public String d;
    public int[] pos;

    public MethodInfo() {
      p = new ArrayList<String>();
      pos = new int[]{-1, -1};
    }
  }

  public static class PathUtils {

    public static void recursiveGenerateClassesFromClassFolder(String path, String prefix, List<String> collector) {
      File f = new File(path);
      if (f.isDirectory()) {
        File[] descents = f.listFiles();
        for (File descent : descents) {
          String descentPath = descent.getPath();
          if (descent.isFile() && descentPath.endsWith(".class") && descentPath.indexOf('$') == -1) {
            collector.add(descentPath.substring(prefix.length(), descentPath.length() - 6).replace("/", "."));
          } else if (descent.isDirectory()) {
            recursiveGenerateClassesFromClassFolder(descent.getPath(), prefix, collector);
          }
        }
      }
    }

    public static List<String> generateClassesFromPath(String path) {
      List<String> toret = new ArrayList<String>();
      if (path.endsWith(".jar") || path.endsWith(".zip")) {
        try {
          for (Enumeration entries = new ZipFile(path).entries(); entries.hasMoreElements();) {
            String entry = entries.nextElement().toString();
            int len = entry.length();
            if (entry.endsWith(".class") && entry.indexOf('$') == -1) {
              toret.add(entry.substring(0, entry.length() - 6).replace("/", "."));
            }
          }
        } catch (Throwable e) {
          //e.printStackTrace();
        }
      } else {
        recursiveGenerateClassesFromClassFolder(path, path, toret);
      }
      return toret;
    }


    public static Set<String> getClassPathElementsFromDir(String dirName) {
      Set<String> toret = new HashSet<String>();
      File dir = new File(dirName);
      if (dir.isDirectory()) {
        String[] items = dir.list();    // use list() instead of listFiles() since the latter are introduced in 1.2
        for (String item : items) {
          File f = new File(dirName + item);
          if (!f.exists()) {
            continue;
          }

          if (item.endsWith(".jar") || item.endsWith(".zip") || item.endsWith(".ZIP")) {
            toret.add(f.toString());
          } else if (items.equals("classes")) {
            if (f.isDirectory())
              toret.add(f.toString());
          }
        }
      }
      return toret;
    }

    public static List<String> getSystemClassPath() {
      List<String> toret = new ArrayList<String>();

      if ("Kaffe".equals(System.getProperty("java.vm.name"))) {
        toret.addAll(getClassPathElementsFromDir(
          System.getProperty("java.home") + File.separator + "share" + File.separator + "kaffe" + File.separator
        )
        );
      } else if ("GNU libgcj".equals(System.getProperty("java.vm.name"))) {
        if (new File(System.getProperty("sun.boot.class.path")).exists()) {
          toret.add(System.getProperty("sun.boot.class.path"));
        }
      }

      if (System.getProperty("java.vendor").toLowerCase(Locale.US).indexOf("microsoft") >= 0) {
        // `*.ZIP` files in `Packages` directory
        toret.addAll(getClassPathElementsFromDir(
          System.getProperty("java.home") + File.separator + "Packages" + File.separator
        )
        );
      } else {
        // the following code works for several kinds of JDK
        // - JDK1.1:		classes.zip
        // - JDK1.2+:		rt.jar
        // - JDK1.4+ of Sun and Apple:	rt.jar + jce.jar + jsse.jar
        // - JDK1.4 of IBM		split rt.jar into core.jar, graphics.jar, server.jar
        // 				combined jce.jar and jsse.jar into security.jar
        // - JDK for MacOS X	split rt.jar into classes.jar, ui.jar in Classes directory
        toret.addAll(getClassPathElementsFromDir(
          System.getProperty("java.home") + File.separator + "lib" + File.separator
        )
        );
        toret.addAll(getClassPathElementsFromDir(
          System.getProperty("java.home") + File.separator + "jre" + File.separator + "lib" + File.separator
        )
        );
        toret.addAll(getClassPathElementsFromDir(
          System.getProperty("java.home") + File.separator + ".." + File.separator + "Classes" + File.separator
        )
        );
      }

      // ext
      String extDirs = System.getProperty("java.ext.dirs");
      for (StringTokenizer st = new StringTokenizer(extDirs, File.pathSeparator); st.hasMoreTokens();) {
        toret.addAll(getClassPathElementsFromDir(
          st.nextToken() + File.separator
        )
        );
      }
      for (Iterator<String> iter = toret.iterator(); iter.hasNext();) {
        String s = iter.next();
        if (false
          || s.indexOf("management-agent.jar") >= 0
          || s.indexOf("deploy.jar") >= 0
          || s.indexOf("javaws.jar") >= 0
          || s.indexOf("plugin.jar") >= 0
          || s.indexOf("jconsole.jar") >= 0
          || s.indexOf("charsets.jar") >= 0
          || s.indexOf("ui.jar") >= 0
          || s.indexOf("sa-jdi.jar") >= 0
          ) {
          iter.remove();
        }
      }
      return toret;
    }
  }

  public String project;
  public List<String> sourcePaths;
  public List<String> classPaths;
  public final ObjectMapper mapper;
  public MessageDigest md5;
  public Pattern systemPattern;
  public String cachePath;
  public String javaJdkSourcePath;

  public JarInfo all;

  public VimTool(String project, List<String> sourcePaths, List<String> classPaths) {
    this.project = project;

    this.all = new JarInfo();

    this.sourcePaths = new ArrayList<String>();
    if (sourcePaths != null) {
      this.sourcePaths.addAll(sourcePaths);
    }
    this.classPaths = new ArrayList<String>();
    if (classPaths != null) {
      this.classPaths.addAll(classPaths);
    }
    this.mapper = new ObjectMapper();
    mapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, false);
    mapper.configure(SerializationConfig.Feature.WRITE_NULL_PROPERTIES, false);
    try {
      String notused = mapper.writeValueAsString("a");
    } catch (IOException e) { }
    try {
      this.md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
    }
    InputStream in = this.getClass().getClassLoader().getResourceAsStream("vimtool.properties");
    if (in != null) {
      Properties props = new Properties();
      try {
        props.load(in);
        systemPattern = Pattern.compile(props.getProperty("excluded_system_paths"));
        cachePath = props.getProperty("cache_folder");
        javaJdkSourcePath = props.getProperty("jdk_source_path");
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      LOG.info("No properties file found");
    }
    String whereIAm = "";
    try {
      whereIAm = new File(VimTool.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParent();
    } catch (Exception e) { }
    try {
      new File(whereIAm + "/tmp/").mkdirs();
    } catch(Exception e) {
    }
    cachePath = whereIAm + "/tmp/";
    LOG.info("Initialize, cachePath: " + cachePath);
  }

  private static List<File> getFileListingNoSort(File start) throws FileNotFoundException {
    List<File> result = new ArrayList<File>();
    File[] filesAndDirs = start.listFiles();
    List<File> filesDirs = Arrays.asList(filesAndDirs);
    for (File file : filesDirs) {
      if (file.isFile()) {
        String name = file.getName();
        if ((name.substring(name.length() - 5, name.length()).equals(".java"))) {
          result.add(file);
        }
      } else {
        //must be a directory
        //recursive call!
        List<File> deeperList = getFileListingNoSort(file);
        result.addAll(deeperList);
      }
    }
    return result;
  }

  public Map<String, List<File>> loadSourcePaths() throws FileNotFoundException {
    Map<String, List<File>> javaFiles = new HashMap<String, List<File>>();
    for (String path : this.sourcePaths) {
      javaFiles.put(path, getFileListingNoSort(new File(path)));
    }
    return javaFiles;
  }

  private String getDigest(String message) {
    md5.reset();
    md5.update(message.getBytes());
    String ret = new BigInteger(1, md5.digest()).toString(16);
    return ret;
  }

  public void reindexSourceFile(final String path) {
    List<File> files = new ArrayList<File>() {{
      add(new File(path));
    }};
    JarInfo j = new JarInfo();
    parseSourceFiles(files, j);
    parseSecondPass(j);
    LOG.info("reindexSourceFile: reparsed file ");
    all.add(j, true);
    //dump to disk
    String sourcePath = getSourcePathForFile(path);
    LOG.info("reindexSourceFile found source path: " + sourcePath);
    if (sourcePath != null && sourcePath != "") {
      String digest = getDigest(sourcePath);
      String outputFileName = this.cachePath + "source_" + digest + ".json";
      File digestFile = new File(outputFileName);

      if (digestFile.exists()) {
        try {
          JarInfo jinfo = mapper.readValue(digestFile, JarInfo.class);
          jinfo.add(j, true);
          mapper.writeValue(digestFile, jinfo);
        } catch(IOException e) { }
      }
    }
  }

  public static class GoodClassComparator implements Comparator<String> {
    public int compare(String l, String r) {
      if (l.indexOf("javax") == 0)
        return 1;
      return l.compareTo(r);
    }
  }

  public String getSourcePathForFile(String path) {
    for (String sourcePath: this.sourcePaths) {
      if (path.indexOf(sourcePath) == 0) {
        return sourcePath;
      }
    }
    return "";
  }

  public void loadSources(Map<String, List<File>> files, boolean forceRefresh) {
    for (Map.Entry<String, List<File>> ff : files.entrySet()) {
      LOG.info("Load sources: " + ff.getKey());
      JarInfo jinfo = new JarInfo();

      String digest = getDigest(ff.getKey());
      String outputFileName = this.cachePath + "source_" + digest + ".json";
      File digestFile = new File(outputFileName);

      if (digestFile.exists() && !forceRefresh) {
        try {
          jinfo = mapper.readValue(digestFile, JarInfo.class);
        } catch (IOException e) {
        }
      } else {
        LOG.info("Reparse sources: " + ff.getKey());
        parseSourceFiles(ff.getValue(), jinfo);
        try {
          mapper
            .writeValue(digestFile, jinfo);
        } catch (IOException e) {
        }
      }
      all.add(jinfo);
    }
  }

  public void parseSourceFiles(List<File> files, JarInfo jinfo) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    DiagnosticCollector<JavaFileObject> diagnosticsCollector = new DiagnosticCollector<JavaFileObject>();

    Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjectsFromFiles(files);
    JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnosticsCollector, null, null, fileObjects);
    Iterable<? extends CompilationUnitTree> compilationUnitTrees = null;
    int retries = 3; boolean ok = true;
    try {
      compilationUnitTrees = task.parse();
    } catch (IOException e) {
      LOG.error(e);
    }
    for (CompilationUnitTree unit : compilationUnitTrees) {
      LOG.info("unit tree source file: " + unit.getSourceFile().getName());
      List<? extends Tree> typeDecls = unit.getTypeDecls();
      LineMap l = unit.getLineMap();
      for (Tree t : typeDecls) {
        if (t.getKind() == Kind.CLASS) {
          SourceClassIntrospector.putClassInfoToMap(jinfo, (JCClassDecl) t, unit, l, null);
        } else {
          // fix static blocks
          //System.out.println("WARNING!!!!!!!!!!");
        }
      }
    }
  }

  public void loadSourcesSecondPass(Map<String, List<File>> files) {
    // all contains the entire dataset
    for (Map.Entry<String, List<File>> ff : files.entrySet()) {
      JarInfo jinfo = new JarInfo();

      String digest = getDigest(ff.getKey());
      String outputFileName = this.cachePath + "source_" + digest + ".json";
      File digestFile = new File(outputFileName);

      try {
        jinfo = mapper.readValue(digestFile, JarInfo.class);
        parseSecondPass(jinfo);
        //all.classes.put(c.fqn, c);
        all.add(jinfo, true);
        mapper.writeValue(digestFile, jinfo);
      } catch (IOException e) {

      }
    }
  }
  
  public void parseSecondPass(JarInfo jinfo) {
    for (Map.Entry<String, ClassInfo> me : jinfo.classes.entrySet()) {
      if (me.getValue().secondPass == 1)
        continue;
      Set<String> goodImports = SourceClassIntrospector.expandImports(me.getValue().imports, all);
      ClassInfo c = me.getValue();
      // all constructors
      for (ConstructorInfo ci : c.ctors) {
        for (int i = 0; i < ci.p.size(); i++) {
          ci.p.set(i, SourceClassIntrospector.getTypeFqn(ci.p.get(i), goodImports));
        }
      }
      for (ConstructorInfo ci : c.declared_ctors) {
        for (int i = 0; i < ci.p.size(); i++) {
          ci.p.set(i, SourceClassIntrospector.getTypeFqn(ci.p.get(i), goodImports));
        }
      }
      // all fields
      for (FieldInfo fi : c.fields) {
        fi.t = SourceClassIntrospector.getTypeFqn(fi.t, goodImports);
      }
      // all fields
      for (FieldInfo fi : c.declared_fields) {
        fi.t = SourceClassIntrospector.getTypeFqn(fi.t, goodImports);
      }
      // all methods
      for (MethodInfo mi : c.methods) {
        for (int i = 0; i < mi.p.size(); i++) {
          mi.p.set(i, SourceClassIntrospector.getTypeFqn(mi.p.get(i), goodImports));
        }
        mi.r = SourceClassIntrospector.getTypeFqn(mi.r, goodImports);
      }
      for (MethodInfo mi : c.declared_methods) {
        for (int i = 0; i < mi.p.size(); i++) {
          mi.p.set(i, SourceClassIntrospector.getTypeFqn(mi.p.get(i), goodImports));
        }
        mi.r = SourceClassIntrospector.getTypeFqn(mi.r, goodImports);
      }
      // we don't need imports no more
      c.imports = new HashSet<String>();
      c.secondPass = 1;
    }
  }

  public boolean filteredClass(String s, Pattern p) {
    Matcher m = p.matcher(s);
    return m.matches();
  }

  public void loadSystemClasses() {
    JarClassLoader jcl = new JarClassLoader();
    for (String path : PathUtils.getSystemClassPath()) {
      JarInfo j = new JarInfo();
      String digest = getDigest(path);
      String outputFileName = this.cachePath + "system_" + digest + ".json";
      File digestFile = new File(outputFileName);
      String outputErrorFileName = this.cachePath + "system_" + digest + ".error";
      File errorFile = new File(outputErrorFileName);
      StringBuffer errorBuffer = new StringBuffer();

      LOG.info("doing system classpath: " + path + ", digest: " + digestFile.getAbsolutePath());
      if (digestFile.exists()) {
        try {
          j = mapper.readValue(digestFile, JarInfo.class);
        } catch (IOException e) {
        }
      } else {
        List<String> classStrings = PathUtils.generateClassesFromPath(path);
        for (String s : classStrings) {
          if (!filteredClass(s, systemPattern)) {
            try {
              Class c = jcl.getSystemClassLoader().loadClass(s);
              LOG.info("  system class: " + c.getName());
              LOG.info("  " + javaJdkSourcePath + c.getName().replace('.', '/') + ".java");
              BinaryClassIntrospector.putClassInfoToMap(j, c, false, javaJdkSourcePath + c.getName().replace('.', '/') + ".java");
            } catch (Throwable e) {
              LOG.error("ERROR parsing system class: " + s);
              errorBuffer.append("ERROR parsing system class: " + s + "\n");
            }
          }
        }
        try {
          mapper.writeValue(digestFile, j);
          BufferedWriter br = new BufferedWriter(new FileWriter(outputErrorFileName));
          br.write(errorBuffer.toString());
          br.close();
        } catch (IOException e) {
          LOG.error("IOException: " + e.toString());
        }
      }

      all.add(j);
    }
  }

  public void loadUserClasses() {
    JarClassLoader jcl = new JarClassLoader();
    for (String path : this.classPaths) {
      jcl.add(path);

      JarInfo j = new JarInfo();
      String digest = getDigest(path);
      String outputFileName = this.cachePath + "user_" + digest + ".json";
      LOG.info("Load user class file: " + path + ", outputFile: " + outputFileName);
      File digestFile = new File(outputFileName);

      String outputErrorFileName = this.cachePath + "user_" + digest + ".error";
      File errorFile = new File(outputErrorFileName);
      StringBuffer errorBuffer = new StringBuffer();

      if (digestFile.exists()) {
        try {
          j = mapper.readValue(digestFile, JarInfo.class);
        } catch (IOException e) {
        }
      } else {
        List<String> classStrings = PathUtils.generateClassesFromPath(path);
        for (String s : classStrings) {
          LOG.info(">> Loading class: " + s);
          if (!filteredClass(s, systemPattern)) {
            try {
              Class c = jcl.loadClass(s);
              BinaryClassIntrospector.putClassInfoToMap(j, c, false, null);
            } catch (Throwable e) {
              LOG.error("ERROR parsing user class: " + s);
              LOG.error(e);
              errorBuffer.append("ERROR parsing user class: " + s + "\n");
            }
          } else {
            LOG.info("** Filtered user class: " + s);
          }
        }
        try {
          mapper.writeValue(new File(outputFileName), j);
          BufferedWriter br = new BufferedWriter(new FileWriter(outputErrorFileName));
          br.write(errorBuffer.toString());
          br.close();
        } catch (IOException e) {
        }
      }
      all.add(j);
    }
  }

  public void init() {
    init(false);
  }

  public void init(boolean forceRefresh) {
    loadSystemClasses();
    if (this.classPaths != null) {
      loadUserClasses();
    }

    if (this.sourcePaths != null) {
      try {
        loadSources(loadSourcePaths(), forceRefresh);
        loadSourcesSecondPass(loadSourcePaths());
      } catch (FileNotFoundException e) {
        LOG.info("File not found: " + e.toString());
      }
    }
  }

  public static class BinaryClassIntrospector {
    private static void putClassInfoToMap(JarInfo collect, Class clazz, boolean isInner, String source) {
      ClassInfo c = new ClassInfo();
      if (source != null) {
        c.source = source;
      } else {
        c.source = "";
      }

      c.flags = Integer.toString(clazz.getModifiers(), 2);
      c.name = clazz.getName().replace('$', '.');
      c.pkg = clazz.getPackage().getName();
      c.fqn = clazz.getName().replace('$', '.');
      Class[] interfaces = clazz.getInterfaces();
      if (clazz.isInterface()) {
        for (Class inter : clazz.getInterfaces()) {
          c.impl.add(inter.getName().replace('$', '.'));
        }
      } else {
        Class superclass = clazz.getSuperclass();
        if (superclass != null && !"java.lang.Object".equals(superclass.getName())) {
          c.parent.add(superclass.getName().replace('$', '.'));
        }
        for (Class inter : clazz.getInterfaces()) {
          c.impl.add(inter.getName().replace('$', '.'));
        }
      }
      putConstructors(c.ctors, clazz.getConstructors());
      putConstructors(c.declared_ctors, clazz.getDeclaredConstructors());

      putFields(c.fields, clazz.getFields(), clazz);
      putFields(c.declared_fields, clazz.getDeclaredFields(), clazz);

      putMethods(c.methods, clazz.getMethods(), clazz);
      putMethods(c.declared_methods, clazz.getDeclaredMethods(), clazz);

      if (!isInner) {
        putClasses(c.classes, collect, clazz.getClasses(), source);
        putClasses(c.declared_classes, collect, clazz.getDeclaredClasses(), source);
      }

      LOG.info("collect.classes.put: " + c.fqn);
      collect.classes.put(c.fqn, c);
      if (!isInner) {
        LOG.info("not inner, putting to packages" + c.fqn);
        putFqnToPackage(collect.packages, c.fqn, true);
      }
    }

    private static void putConstructors(List<ConstructorInfo> bag, Constructor[] constructors) {
      for (Constructor ctor : constructors) {
        ConstructorInfo cinfo = new ConstructorInfo();
        cinfo.m = ctor.getModifiers();
        for (Class type : ctor.getParameterTypes()) {
          cinfo.p.add(type.getName());
        }
        cinfo.d = ctor.toString();
        bag.add(cinfo);
      }
    }

    private static void putFields(List<FieldInfo> bag, Field[] fields, Class clazz) {
      for (Field field : fields) {
        FieldInfo f = new FieldInfo();
        f.n = field.getName();
        f.m = Integer.toString(field.getModifiers(), 2);
        if (!field.getDeclaringClass().getName().equals(clazz.getName())) {
          f.c = field.getDeclaringClass().getName();
        }
        f.t = field.getType().getName();
        bag.add(f);
      }
    }

    private static void putMethods(List<MethodInfo> bag, Method[] methods, Class clazz) {
      for (Method method : methods) {
        if (!method.getDeclaringClass().getName().equals("java.lang.Object")) {
          MethodInfo m = new MethodInfo();
          m.m = Integer.toString(method.getModifiers(), 2);
          m.n = method.getName();
          if (!method.getDeclaringClass().getName().equals(clazz.getName())) {
            m.c = method.getDeclaringClass().getName();
          }
          m.r = method.getReturnType().getName();
          for (Class type : method.getParameterTypes()) {
            m.p.add(type.getName());
          }
          m.d = method.toString();
          bag.add(m);
        }
      }
    }

    private static void putClasses(List<String> bag, JarInfo collect, Class[] classes, String source) {
      for (Class c : classes) {
        bag.add(c.getName().replace('$', '.'));
        putClassInfoToMap(collect, c, true, source);
      }
    }
  }

  public static class SourceClassIntrospector {
    public static Map<String, String> primitiveTypes = new HashMap<String, String>() {{
      put("boolean", "Z");
      put("byte", "B");
      put("char", "C");
      put("short", "S");
      put("int", "I");
      put("long", "J");
      put("float", "F");
      put("double", "D");
    }};

    public static String[] javaLangClasses = new String[]{"CharSequence", "Cloneable", "Comparable", "Runnable",
      "Boolean", "Byte", "Character", "Character.Subset", "Character.UnicodeBlock", "Class", "ClassLoader", "Compiler",
      "Double", "Float", "InheritableThreadLocal", "Integer", "Long", "Math", "Number", "Object", "Package", "Process",
      "Runtime", "RuntimePermission", "SecurityManager", "Short", "StackTraceElement", "StrictMath", "String",
      "StringBuffer", "System", "Thread", "ThreadGroup", "ThreadLocal", "Throwable", "Void", "ArithmeticException",
      "ArrayIndexOutOfBoundsException", "ArrayStoreException", "ClassCastException", "ClassNotFoundException",
      "CloneNotSupportedException", "Exception", "IllegalAccessException", "IllegalArgumentException",
      "IllegalMonitorStateException", "IllegalStateException", "IllegalThreadStateException",
      "IndexOutOfBoundsException", "InstantiationException", "InterruptedException", "NegativeArraySizeException",
      "NoSuchFieldException", "NoSuchMethodException", "NullPointerException", "NumberFormatException",
      "RuntimeException", "SecurityException", "StringIndexOutOfBoundsException", "UnsupportedOperationException",
      "AbstractMethodError", "AssertionError", "ClassCircularityError", "ClassFormatError", "Error",
      "ExceptionInInitializerError", "IllegalAccessError", "IncompatibleClassChangeError", "InstantiationError",
      "InternalError", "LinkageError", "NoClassDefFoundError", "NoSuchFieldError", "NoSuchMethodError",
      "OutOfMemoryError", "StackOverflowError", "ThreadDeath", "UnknownError", "UnsatisfiedLinkError",
      "UnsupportedClassVersionError", "VerifyError", "VirtualMachineError"};

    public static Set<String> expandImports(Set<String> imports, JarInfo all) {
      Set<String> ret = new HashSet<String>();
      for (String imp : imports) {
        if (imp.substring(imp.lastIndexOf(".") + 1, imp.length()).equals("*")) {
          String p = imp.substring(0, imp.lastIndexOf("."));
          if (all.packages.containsKey(p)) {
            for (String klass : all.packages.get(p).classes) {
              ret.add(p + "." + klass);
            }
          }
        } else {
          ret.add(imp);
        }
      }
      return ret;
    }

    public static String getTypeFqn(String type, Set<String> imports) {
      int arrayishness = 0;
      if (type.indexOf("<") >= 0) {
        type = type.substring(0, type.indexOf("<"));
      }
      if (type.indexOf("[") >= 0) {
        String p = type.substring(type.indexOf('['), type.length());
        type = type.substring(0, type.indexOf('['));
        arrayishness = p.length() / 2;
      }
      String out = "";
      for (int i = 0; i < arrayishness; i++) {
        out += "[";
      }
      // primitive ?
      if (primitiveTypes.containsKey(type)) {
        out += primitiveTypes.get(type);
        return out;
      }
      // java lang ?
      for (int i = 0; i < javaLangClasses.length; i++) {
        if (type.equals(javaLangClasses[i])) {
          if (arrayishness > 0) {
            out += "Ljava.lang." + type + ";";
            return out;
          } else {
            return "java.lang."  + type;
          }
        }
      }
      //imports ?
      for (String imp : imports) {
        String c = imp.substring(imp.lastIndexOf(".") + 1, imp.length());
        if (c.equals(type)) {
          if (arrayishness > 0) {
            out += "L" + imp + ";";
            return out;
          } else {
            return imp;
          }
        }
      }
      return type;
    }

    public static void putClassInfoToMap(JarInfo collect, JCClassDecl tree, CompilationUnitTree unit, LineMap l,
                                         ClassInfo parent) {
      ClassInfo c = new ClassInfo();
      c.source = unit.getSourceFile().toUri().toASCIIString();
      List<? extends ImportTree> imports = unit.getImports();
      for (ImportTree t : imports) {
        JCImport tAsJC = (JCImport) t;
        c.imports.add(tAsJC.qualid.toString());
      }
      c.flags = Integer.toString((int) tree.mods.flags, 2);
      c.pkg = unit.getPackageName().toString();
      if (parent != null) {
        //c.name = parent.name + "." + tree.name.toString();
        c.fqn = c.name = parent.fqn + "." + tree.name.toString();
      } else {
        //c.name = tree.name.toString();
        c.fqn = c.name = unit.getPackageName().toString() + "." + tree.name.toString();
      }

      if (Modifier.isInterface((int) tree.mods.flags)) {
        for (JCExpression impl : tree.implementing) {
          c.impl.add(impl.toString());
        }
      } else {
        if (tree.extending != null)
          c.parent.add(tree.extending.toString());
        for (JCExpression impl : tree.implementing) {
          c.impl.add(impl.toString());
        }
      }

      List<ConstructorInfo> ctors = new ArrayList<ConstructorInfo>();
      List<FieldInfo> fields = new ArrayList<FieldInfo>();
      List<MethodInfo> methods = new ArrayList<MethodInfo>();

      for (JCTree m : tree.defs) {
        switch (m.getKind()) {
          case CLASS:
            JCClassDecl inner = (JCClassDecl) m;
            putClassInfoToMap(collect, inner, unit, l, c);
            c.classes.add(c.fqn + "." + inner.name.toString());
            break;
          case METHOD:
            JCMethodDecl method = (JCMethodDecl) m;
            if (method.name.toString().equals("<init>")) {
              ConstructorInfo cinfo = new ConstructorInfo();
              cinfo.m = (int) method.mods.flags;
              String s = "";
              for (JCVariableDecl jv : method.getParameters()) {
                cinfo.p.add(jv.vartype.toString());
                s += jv.toString() + ", ";
              }
              cinfo.d = Modifier.toString(cinfo.m) + " " + c.name + "(" +
                (s.length() > 0 ? s.substring(0, s.length() - 2) : "") + ")";
              ctors.add(cinfo);
              cinfo.pos[0] = (int) l.getLineNumber((long) method.getPreferredPosition());
              cinfo.pos[1] = (int) l.getColumnNumber((long) method.getPreferredPosition());
            } else {
              MethodInfo minfo = new MethodInfo();
              minfo.m = Integer.toString((int) method.mods.flags, 2);
              minfo.n = method.name.toString();
              minfo.r = method.restype.toString();
              String s = "";
              for (JCVariableDecl jv : method.getParameters()) {
                minfo.p.add(jv.vartype.toString());
                s += jv.toString() + ", ";
              }
              minfo.d = Modifier.toString((int) method.mods.flags) + " " + method.name + "(" +
                (s.length() > 0 ? s.substring(0, s.length() - 2) : "") + ")";
              minfo.pos[0] = (int) l.getLineNumber((long) method.getPreferredPosition());
              minfo.pos[1] = (int) l.getColumnNumber((long) method.getPreferredPosition());
              methods.add(minfo);
            }
            break;
          case VARIABLE:
            JCVariableDecl decl = (JCVariableDecl) m;
            FieldInfo f = new FieldInfo();
            f.n = decl.name.toString();
            f.m = Integer.toString((int) decl.mods.flags, 2);
            f.c = "";
            f.t = decl.vartype.toString();
            f.pos[0] = (int) l.getLineNumber((long) decl.getPreferredPosition());
            f.pos[1] = (int) l.getColumnNumber((long) decl.getPreferredPosition());
            fields.add(f);
            break;
          default:
            //System.out.println("UNKNOWN member: " + m.getKind() + "\r\r" + m.toString());
        }
      }
      c.ctors = ctors;
      c.fields = fields;
      c.methods = methods;
      collect.classes.put(c.fqn, c);
      if (parent == null)
        putFqnToPackage(collect.packages, c.fqn, true);
    }

    public String getFqnForContext(String name) {
      return "";
    }
  }

  public static void putFqnToPackage(Map<String, PackageInfo> packages, String fqn, boolean isClass) {
    String[] packageAndClass = fqn.split("\\.");

    int stopIndex = packageAndClass.length;
    if (isClass) {
      for (int i = 0; i < packageAndClass.length; i++) {
        if (Character.isUpperCase(packageAndClass[i].charAt(0))) {
          stopIndex = i;
          break;
        }
      }
    }
    
    String acc = packageAndClass[0];
    for (int i = 1; i < stopIndex; i++) {
      if (!packages.containsKey(acc)) {
        packages.put(acc, new PackageInfo());
      }
      packages.get(acc).subpackages.add(packageAndClass[i]);
      acc = acc + "." + packageAndClass[i];
    }

    if (isClass) {
      if (!packages.containsKey(acc)) {
        packages.put(acc, new PackageInfo());
      }
      if (stopIndex < packageAndClass.length && isClass) {
        packages.get(acc).classes.add(packageAndClass[stopIndex]);
      }      
    } else {
      if (!packages.containsKey(acc)) {
        packages.put(acc, new PackageInfo());
      }
    }
  }

  public List<String> complete(int line, int col) {
    return null;
  }

  public String outputListOfClasses(List<String> classNames) {
    StringBuffer out = new StringBuffer();
    out.append("[");
    List<String> cache = new ArrayList<String>();
    for (String c: classNames) {
      if (all.classes.containsKey(c) && !cache.contains(c)) {
        cache.add(c);
        out.append(all.classes.get(c));
        out.append(",");
      }
    }
    out.append("]");
    return out.toString();
  }

  public String findClassesLikeName(String classNamePiece) {

    ArrayList<String> tmp =  new ArrayList<String>();

    for (String e: all.classes.keySet()) {
      int l = e.indexOf("." + classNamePiece);
      int d = e.lastIndexOf(".");
      int r = e.indexOf("$" + classNamePiece);
      if (l >= d || r >= d) {
        tmp.add(e);
      }
    }
    Collections.sort(tmp, new GoodClassComparator());

    StringBuffer out = new StringBuffer();

    out.append("[");
    for (String e: tmp) {
      out.append("'" + e + "'");
      out.append(",");
    }
    out.append("]");
    return out.toString();
  }

  public String findClass(String className, boolean single) {
    if (all.classes.containsKey(className)) {
      ClassInfo ci = all.classes.get(className);
      if (single) {
        try {
          return mapper.writeValueAsString(ci);
        } catch (IOException e) {
          return "";
        }
      } else {
        List<String> cache = new ArrayList<String>();
        StringBuffer out = new StringBuffer();
        out.append("[");
        LOG.info("findClass outputs : " + className);
        try {
          out.append(mapper.writeValueAsString(ci));
          cache.add(className);
          out.append(",");
        
          for (String c: ci.impl) {
            LOG.info("**findClass outputs : " + c);
            if (all.classes.containsKey(c) && !cache.contains(c)) {
              LOG.info("findClass outputs : " + c);
              cache.add(c);
              out.append(mapper.writeValueAsString(all.classes.get(c)));
              out.append(",");
            }
          }
          for (String c: ci.parent) {
            LOG.info("**findClass outputs : " + c);
            if (all.classes.containsKey(c) && !cache.contains(c)) {
              LOG.info("findClass outputs : " + c);
              cache.add(c);
              out.append(mapper.writeValueAsString(all.classes.get(c)));
              out.append(",");
            }
          }
          for (String c: ci.classes) {
            LOG.info("**findClass outputs : " + c);
            if (all.classes.containsKey(c) && !cache.contains(c)) {
              LOG.info("findClass outputs : " + c);
              cache.add(c);
              out.append(mapper.writeValueAsString(all.classes.get(c)));
              out.append(",");
            }
          }
          for (String c: ci.declared_classes) {
            LOG.info("**findClass outputs : " + c);
            if (all.classes.containsKey(c) && !cache.contains(c)) {
              LOG.info("findClass outputs : " + c);
              cache.add(c);
              out.append(mapper.writeValueAsString(all.classes.get(c)));
              out.append(",");
            }
          }
        } catch (IOException e) {
          LOG.error("Exception: " + e.toString());
        }
        
        out.append("]");

        return out.toString();
      }
    }
    return "";
  }

  public String checkExistedAndRead(String fqns) {
    Map<String, ClassInfo> classes = new HashMap<String, ClassInfo>();
    Map<String, PackageInfo> packages = new HashMap<String, PackageInfo>();
    
    for (StringTokenizer st = new StringTokenizer(fqns, ","); st.hasMoreTokens(); ) {
      String fqn = st.nextToken();
      if (all.classes.containsKey(fqn)) {
        classes.put(fqn, all.classes.get(fqn));
      } else if (all.packages.containsKey(fqn)) {
        packages.put(fqn, all.packages.get(fqn));
      }
    }

    if (classes.size() > 0 || packages.size() > 0) {
      StringBuffer sb = new StringBuffer(4096);
      sb.append("{");
      for (Map.Entry<String, ClassInfo> c: classes.entrySet()) {
        String content = "";
        try {
          content = mapper.writeValueAsString(c.getValue());
        } catch (IOException e) {}
        sb.append("'").append( c.getKey().replace('$', '.') ).append("':").append(content).append(",");
      }
      for (Map.Entry<String, PackageInfo> p: packages.entrySet()) {
        String content = "";
        try {
          content = mapper.writeValueAsString(p.getValue());
        } catch (IOException e) {}
        sb.append("'").append( p.getKey().replace('$', '.') ).append("':").append(content).append(",");
      }
      sb.append("}");
      return sb.toString();
    }
    return "";
  }

  public String listAllPackages() {
    try {
      return mapper.writeValueAsString(all.packages);
    } catch (IOException e) {
      return "";
    }
  }

  public List<String> goToDeclaration() {
    return null;
  }

  public static class Runner {
    @Parameter(names = "-C", description = "Whole class info")
    public Boolean doClassInfo = false;

    @Parameter(names = "-d", description = "default strategy")
    public Boolean useDefaultStrategy = false;

    @Parameter(names = "-single", description = "single")
    public Boolean outputSingleClass = false;
    
    @Parameter(names = "-e", description = "check existed")
    public Boolean doCheckExisted = false;

    @Parameter(names = "-E", description = "check existed and read")
    public Boolean doCheckExistedAndRead = false;

    @Parameter(names = "-p", description = "list package content for class")
    public Boolean doListPackageContentForClass = false;

    @Parameter(names = "-P", description = "list all packages content")
    public Boolean doListAllPackageContent = false;

    @Parameter(names = "-i", description = "initialize")
    public Boolean doInitialize = false;

    @Parameter(names = "-n", description = "initialize")
    public Boolean doSearchForName = false;

    @Parameter(names = "-fffqn", description = "initialize")
    public Boolean doSearchForFqn = false;

    @Parameter(names = "-indent", description = "indent")
    public Boolean doIndent = false;
    
    @Parameter(names = "-classes", description = "class path")
    public String classPath = "";

    @Parameter(names = "-sources", description = "source path")
    public String sourcePath = "";

    @Parameter(names = "-class", description = "class")
    public String klass = "";

    @Parameter(names = "-file", description = "file to reindex")
    public String file = "";

    @Parameter(names = "-reindex", description = "reindex sources action")
    public Boolean doReindex = false;
    
    public VimTool vt;

    public Runner() {
      vt = null;
    }

    public void reset() {
      doClassInfo = false;
      useDefaultStrategy = false;
      outputSingleClass = false;
      doCheckExisted = false;
      doCheckExistedAndRead = false;
      doListPackageContentForClass = false;
      doListAllPackageContent = false;
      doInitialize = false;
      doSearchForName = false;
      doSearchForFqn = false;
      doIndent = false;
      klass = "";
      file = "";
      doReindex = false;
    }

    public void execute() {
      List<String> splitClassPath = new ArrayList<String>();
      List<String> splitSourcePath = new ArrayList<String>();
      if (classPath != "" && classPath != null) {
        splitClassPath = Arrays.asList(classPath.split(":"));
      }
      if (sourcePath != "" && sourcePath != null) {
        splitSourcePath = Arrays.asList(sourcePath.split(":"));
      }

      if (vt == null || doInitialize) {
        vt = new VimTool("", splitSourcePath, splitClassPath);
        vt.init(doInitialize);
      }
      vt.mapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, doIndent);

      String toPrint = "";
      if (doClassInfo) {
        LOG.info("COMMAND: doClassInfo: " + klass + ", " + outputSingleClass);
        toPrint = vt.findClass(klass, outputSingleClass);
      } else if (doListAllPackageContent) {
        LOG.info("COMMAND: listAllPackages");
        toPrint = vt.listAllPackages();
      } else if (doListPackageContentForClass) {
        LOG.info("COMMAND: listPackageContentForClass - NOT IMPLEMENTED");
      } else if (doCheckExisted) {
        LOG.info("COMMAND: checkExisted - NOT IMPLEMENTED");
      } else if (doCheckExistedAndRead) {
        LOG.info("COMMAND: checkExistedAndRead: " + klass);
        toPrint = vt.checkExistedAndRead(klass);
      } else if (doSearchForName) {
        LOG.info("COMMAND: searchForName: " + klass);
        toPrint = vt.findClassesLikeName(klass);
      } else if (doSearchForFqn) {
        LOG.info("COMMAND: searchForFqn - NOT IMPLEMENTED");
      } else if (doReindex) {
        if (file != "") {
          LOG.info("COMMAND: doReindex with file: " + file);
          vt.reindexSourceFile(file);
        } else {
          LOG.info("COMMAND: doReindex ALL");
          vt.init(true);
        }
      }
      if (doIndent) {
        System.out.print(toPrint);
      } else {
        System.out.print(toPrint.replace("\n", "").replace("\"", "'"));
      }
    }
  }

  public static Runner runner = new Runner();

  public static void nailMain(com.martiansoftware.nailgun.NGContext context) {
    runner.reset();
    new JCommander(runner, context.getArgs());
    runner.execute();
  }

  public static void main(String[] args) {
    runner.reset();
    new JCommander(runner, args);
    runner.execute();
  }
}
