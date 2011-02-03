package org.pitest;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.pitest.functional.F;
import org.pitest.functional.FCollection;
import org.pitest.functional.predicate.Predicate;
import org.pitest.internal.ClassPath;
import org.pitest.internal.IsolationUtils;
import org.pitest.internal.classloader.DefaultPITClassloader;
import org.pitest.mutationtest.CodeCentricReport;
import org.pitest.mutationtest.DefaultMutationConfigFactory;
import org.pitest.mutationtest.Mutator;
import org.pitest.mutationtest.ReportOptions;
import org.pitest.mutationtest.instrument.KnownLocationJavaAgentJarFinder;
import org.pitest.util.Glob;

/**
 * Goal which runs a coverage mutation report
 * 
 * @goal pit
 * 
 * @requiresDependencyResolution test
 * 
 * @phase integration-test
 */
public class PitMojo extends AbstractMojo {

  /**
   * Classes to include in mutation test
   * 
   * @parameter
   * 
   */
  private List<String>          targetClasses;

  /**
   * Classes in scope for dependency and coverage analysis
   * 
   * @parameter
   * 
   */
  private List<String>          inScopeClasses;

  /**
   * Base directory where all reports are written to.
   * 
   * @parameter default-value="${project.build.directory}/pit-reports"
   */
  private File                  reportsDirectory;

  /**
   * Maximum distance to look from test to class
   * 
   * @parameter
   */
  private int                   maxDependencyDistance;

  /**
   * Maximum distance to look from test to class
   * 
   * @parameter
   */
  private List<String>          mutators;

  /**
   * <i>Internal</i>: Project to interact with.
   * 
   * @parameter expression="${project}"
   * @required
   * @readonly
   */
  private MavenProject          project;

  /**
   * <i>Internal</i>: Map of plugin artifacts.
   * 
   * @parameter expression="${plugin.artifactMap}"
   * @required
   * @readonly
   */
  private Map<String, Artifact> pluginArtifactMap;

  /**
   * Location of the local repository.
   * 
   * @parameter expression="${localRepository}"
   * @readonly
   * @required
   */
  protected ArtifactRepository  localRepository;

  /**
   * Used to look up Artifacts in the remote repository.
   * 
   * @parameter expression=
   *            "${component.org.apache.maven.artifact.factory.ArtifactFactory}"
   * @required
   * @readonly
   */
  protected ArtifactFactory     factory;

  @SuppressWarnings("unchecked")
  public void execute() throws MojoExecutionException {
    final Set<String> classPath = new HashSet<String>();
    try {
      classPath.addAll(this.project.getTestClasspathElements());
      classPath.addAll(this.project.getCompileClasspathElements());
      classPath.addAll(this.project.getRuntimeClasspathElements());
      classPath.addAll(this.project.getSystemClasspathElements());

    } catch (final DependencyResolutionRequiredException e1) {
      getLog().info(e1);
      e1.printStackTrace();
    }

    final Artifact pitVersionInfo = this.pluginArtifactMap
        .get("org.pitest:pitest");

    final ReportOptions data = new ReportOptions();
    data.setClassPathElements(classPath);
    data.setIsTestCentric(false);
    data.setDependencyAnalysisMaxDistance(this.maxDependencyDistance);

    data.setTargetClasses(determineTargetClasses());
    data.setClassesInScope(determineClassesInScope());

    data.setReportDir(this.reportsDirectory.getAbsolutePath());

    data.setMutators(determineMutators());

    final List<String> sourceRoots = new ArrayList<String>();
    sourceRoots.addAll(this.project.getCompileSourceRoots());
    sourceRoots.addAll(this.project.getTestCompileSourceRoots());

    data.setSourceDirs(stringsTofiles(sourceRoots));

    System.out.println("Running report with " + data);

    final CodeCentricReport report = new CodeCentricReport(data,
        new KnownLocationJavaAgentJarFinder(pitVersionInfo.getFile()
            .getAbsolutePath()), true);

    // FIXME will we get a clash between junit & possibly PIT jars by using the
    // plugin loader?
    final ClassLoader loader = new DefaultPITClassloader(data
        .getClassPath(true).getOrElse(new ClassPath()),
        IsolationUtils.getContextClassLoader());
    ClassLoader original = IsolationUtils.getContextClassLoader();

    try {
      IsolationUtils.setContextClassLoader(loader);

      final Runnable run = (Runnable) IsolationUtils.cloneForLoader(report,
          loader);

      run.run();

    } catch (final Exception e) {
      throw new MojoExecutionException("fail", e);
    } finally {
      IsolationUtils.setContextClassLoader(original);
    }
  }

  private Collection<Mutator> determineMutators() {
    if (this.mutators != null) {
      return FCollection.map(this.mutators, stringToMutator());
    } else {
      return DefaultMutationConfigFactory.DEFAULT_MUTATORS;
    }
  }

  private F<String, Mutator> stringToMutator() {
    return new F<String, Mutator>() {
      public Mutator apply(String a) {
        return Mutator.valueOf(a);
      }

    };
  }

  private Collection<Predicate<String>> determineClassesInScope() {
    return returnOrDefaultToClassesLikeGroupName(this.inScopeClasses);
  }

  private Collection<Predicate<String>> determineTargetClasses() {
    return returnOrDefaultToClassesLikeGroupName(this.targetClasses);
  }

  private Collection<Predicate<String>> returnOrDefaultToClassesLikeGroupName(
      Collection<String> filters) {
    if (filters == null) {
      return Collections.<Predicate<String>> singleton(new Glob(this.project
          .getGroupId() + "*"));
    } else {
      return FCollection.map(filters, Glob.toGlobPredicate());
    }
  }

  private Collection<File> stringsTofiles(final List<String> sourceRoots) {
    return FCollection.map(sourceRoots, stringToFile());
  }

  private F<String, File> stringToFile() {
    return new F<String, File>() {
      public File apply(final String a) {
        return new File(a);
      }

    };
  }
}