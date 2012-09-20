/*
 * Copyright 2012 Henry Coles
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.pitest.coverage;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import org.pitest.classinfo.ClassInfo;
import org.pitest.classinfo.CodeSource;
import org.pitest.coverage.execute.CoverageOptions;
import org.pitest.coverage.execute.CoverageProcess;
import org.pitest.coverage.execute.CoverageResult;
import org.pitest.coverage.execute.LaunchOptions;
import org.pitest.coverage.export.CoverageExporter;
import org.pitest.extension.Configuration;
import org.pitest.functional.F;
import org.pitest.functional.FCollection;
import org.pitest.functional.Prelude;
import org.pitest.functional.SideEffect1;
import org.pitest.help.Help;
import org.pitest.help.PitHelpError;
import org.pitest.mutationtest.Timings;
import org.pitest.util.JavaAgent;
import org.pitest.util.Log;
import org.pitest.util.ProcessArgs;
import org.pitest.util.SocketFinder;
import org.pitest.util.Unchecked;

public class DefaultCoverageGenerator implements CoverageGenerator {

  private final static Logger   LOG = Log.getLogger();

  private final CoverageOptions coverageOptions;
  private final LaunchOptions   launchOptions;
  private final CodeSource      code;
  private final Timings         timings;
  private final File            workingDir;
  private final CoverageExporter exporter;
  private final boolean showProgress;

  public DefaultCoverageGenerator(final File workingDir,
      final CoverageOptions coverageOptions, final LaunchOptions launchOptions,
      final CodeSource code, CoverageExporter exporter, final Timings timings, final boolean showProgress) {
    this.coverageOptions = coverageOptions;
    this.code = code;
    this.launchOptions = launchOptions;
    this.timings = timings;
    this.workingDir = workingDir;
    this.exporter = exporter;
    this.showProgress = showProgress;
  }

  public CoverageData calculateCoverage() {
    try {
      final long t0 = System.currentTimeMillis();

      this.timings.registerStart(Timings.Stage.SCAN_CLASS_PATH);
      final Collection<ClassInfo> tests = this.code.getTests();
      this.timings.registerEnd(Timings.Stage.SCAN_CLASS_PATH);

      final CoverageData coverage = new CoverageData(this.code);

      this.timings.registerStart(Timings.Stage.COVERAGE);
      gatherCoverageData(tests, coverage);
      this.timings.registerEnd(Timings.Stage.COVERAGE);

      final long time = (System.currentTimeMillis() - t0) / 1000;

      LOG.info("Calculated coverage in " + time + " seconds.");

      verifyBuildSuitableForMutationTesting(coverage);

      exporter.recordCoverage(coverage.createLineCoverage());
      
      return coverage;

    } catch (final PitHelpError phe) {
      throw phe;
    } catch (final Exception e) {
      throw Unchecked.translateCheckedException(e);
    }
  }

  private void verifyBuildSuitableForMutationTesting(final CoverageData coverage) {
    if (!coverage.allTestsGreen()) {
      throw new PitHelpError(Help.FAILING_TESTS);
    }
  }

  private void gatherCoverageData(final Collection<ClassInfo> tests,
      final CoverageData coverage) throws IOException, InterruptedException,
      ExecutionException {

    final List<String> filteredTests = FCollection
        .map(tests, classInfoToName());

    final SideEffect1<CoverageResult> handler = resultProcessor(coverage);

    final SocketFinder sf = new SocketFinder();
    final ServerSocket socket = sf.getNextAvailableServerSocket();

    final CoverageProcess process = new CoverageProcess(ProcessArgs
        .withClassPath(this.code.getClassPath()).andBaseDir(this.workingDir)
        .andJVMArgs(this.launchOptions.getChildJVMArgs())
        .andJavaAgentFinder(this.launchOptions.getJavaAgentFinder())
        .andStderr(logInfo())
        .andStdout(captureStandardOutIfVerbose()), this.coverageOptions,
        socket, filteredTests, handler);

    process.start();
    process.waitToDie();
  }

  private static F<ClassInfo, String> classInfoToName() {
    return new F<ClassInfo, String>() {
      public String apply(final ClassInfo a) {
        return a.getName().asInternalName();
      }

    };
  }

  private SideEffect1<String> captureStandardOutIfVerbose() {
    if (this.coverageOptions.isVerbose()) {
      return log();
    } else {
      return Prelude.noSideEffect(String.class);
    }
  }
    
  private SideEffect1<String> logInfo() {
    return new SideEffect1<String>() {
      public void apply(String a) {
        LOG.info("SLAVE : " + a);
      } 
    };
  }

  private SideEffect1<String> log() {
    return new SideEffect1<String>() {
      public void apply(String a) {
        LOG.fine("SLAVE : " + a);
      } 
    };
  }

  private SideEffect1<CoverageResult> resultProcessor(
      final CoverageData coverage) {
    return new SideEffect1<CoverageResult>() {
      private final String[] spinner = new String[] {"\u0008/", "\u0008-", "\u0008\\", "\u0008|" };
      int i = 0;
      public void apply(final CoverageResult cr) {
        coverage.calculateClassCoverage(cr);
        if (showProgress ) {
          System.out.printf("%s", spinner[i % spinner.length]);
        }
        i++;
      }

    };
  }
  
  public Configuration getConfiguration() {
    return this.coverageOptions.getPitConfig();
  }

  public JavaAgent getJavaAgent() {
    return this.launchOptions.getJavaAgentFinder();
  }

}
