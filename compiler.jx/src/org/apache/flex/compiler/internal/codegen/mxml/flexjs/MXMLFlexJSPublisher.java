/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.flex.compiler.internal.codegen.mxml.flexjs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.flex.compiler.clients.problems.ProblemQuery;
import org.apache.flex.compiler.codegen.js.IJSPublisher;
import org.apache.flex.compiler.config.Configuration;
import org.apache.flex.compiler.internal.codegen.as.ASEmitterTokens;
import org.apache.flex.compiler.internal.codegen.js.JSSharedData;
import org.apache.flex.compiler.internal.codegen.js.flexjs.JSFlexJSEmitterTokens;
import org.apache.flex.compiler.internal.codegen.js.goog.JSGoogEmitterTokens;
import org.apache.flex.compiler.internal.codegen.js.goog.JSGoogPublisher;
import org.apache.flex.compiler.internal.driver.js.flexjs.JSCSSCompilationSession;
import org.apache.flex.compiler.internal.driver.js.goog.JSGoogConfiguration;
import org.apache.flex.compiler.internal.graph.GoogDepsWriter;
import org.apache.flex.compiler.internal.projects.FlexJSProject;
import org.apache.flex.compiler.utils.JSClosureCompilerWrapper;

public class MXMLFlexJSPublisher extends JSGoogPublisher implements
        IJSPublisher
{

    public static final String FLEXJS_OUTPUT_DIR_NAME = "bin";
    public static final String FLEXJS_INTERMEDIATE_DIR_NAME = "js-debug";
    public static final String FLEXJS_RELEASE_DIR_NAME = "js-release";

    class DependencyRecord
    {
        String path;
        String deps;
        String line;
        int lineNumber;
    }
    
    class DependencyLineComparator implements Comparator<DependencyRecord> {
        @Override
        public int compare(DependencyRecord o1, DependencyRecord o2) {
            return new Integer(o1.lineNumber).compareTo(o2.lineNumber);
        }
    }
    
    public MXMLFlexJSPublisher(Configuration config, FlexJSProject project)
    {
        super(config);

        this.isMarmotinniRun = ((JSGoogConfiguration) configuration)
                .getMarmotinni() != null;
        this.outputPathParameter = configuration.getOutput();
        this.useStrictPublishing = ((JSGoogConfiguration) configuration)
                .getStrictPublish();

        this.project = project;
    }

    private FlexJSProject project;

    private boolean isMarmotinniRun;
    private String outputPathParameter;
    private boolean useStrictPublishing;
    private String closureLibDirPath;

    @Override
    public File getOutputFolder()
    {
        // (erikdebruin) - If there is a -marmotinni switch, we want
        //                 the output redirected to the directory it specifies.
        //               - If there is an -output switch, use that path as the 
        //                 output parent folder.
        if (isMarmotinniRun)
        {
            outputParentFolder = new File(
                    ((JSGoogConfiguration) configuration).getMarmotinni());
        }
        else if (outputPathParameter != null)
        {
            outputParentFolder = new File(outputPathParameter);
            // FB usually specified -output <project-path>/bin-release/app.swf
            if (outputPathParameter.contains(".swf"))
                outputParentFolder = outputParentFolder.getParentFile().getParentFile();
        }
        else
        {
            outputParentFolder = new File(
                    configuration.getTargetFileDirectory()).getParentFile();
        }

        outputParentFolder = new File(outputParentFolder,
                FLEXJS_OUTPUT_DIR_NAME);

        outputFolder = new File(outputParentFolder, File.separator
                + FLEXJS_INTERMEDIATE_DIR_NAME);

        // (erikdebruin) Marmotinni handles file management, so we 
        //               bypass the setup.
        if (!isMarmotinniRun)
            setupOutputFolder();

        return outputFolder;
    }

    @Override
    public boolean publish(ProblemQuery problems) throws IOException
    {
        @SuppressWarnings("unused")
		boolean ok;
        //boolean subsetGoog = true;
        
        final String intermediateDirPath = outputFolder.getPath();
        final File intermediateDir = new File(intermediateDirPath);
        File srcDir = new File(configuration.getTargetFile());
        srcDir = srcDir.getParentFile();

        final String projectName = FilenameUtils.getBaseName(configuration
                .getTargetFile());
        final String outputFileName = projectName
                + "." + JSSharedData.OUTPUT_EXTENSION;

        File releaseDir = new File(outputParentFolder, FLEXJS_RELEASE_DIR_NAME);
        final String releaseDirPath = releaseDir.getPath();

        if (!isMarmotinniRun)
        {
            if (releaseDir.exists()) {
                org.apache.commons.io.FileUtils.deleteQuietly(releaseDir);
            }

            if(!releaseDir.mkdirs()) {
                throw new IOException(
                        "Unable to create release directory at " + releaseDir.getAbsolutePath());
            }
        }

        // If the closure-lib parameter is empty we'll try to find the resources
        // in the classpath, dump its content to the output directory and use this
        // as closure-lib parameter.
        if(((JSGoogConfiguration) configuration).isClosureLibSet()) {
            closureLibDirPath = ((JSGoogConfiguration) configuration).getClosureLib();
        } else {
            // Check if the "goog/deps.js" is available in the classpath.
            URL resource = Thread.currentThread().getContextClassLoader().getResource("goog/deps.js");
            if(resource != null) {
                File closureLibDir = new File(intermediateDir.getParent(), "closure");

                // Only create and dump the content, if the directory does not exists.
                if(!closureLibDir.exists()) {
                    if(!closureLibDir.mkdirs()) {
                        throw new IOException(
                                "Unable to create directory for closure-lib at " + closureLibDir.getAbsolutePath());
                    }

                    // Strip the url of the parts we don't need.
                    // Unless we are not using some insanely complex setup
                    // the resource will always be on the same machine.
                    String resourceJarPath = resource.getFile();
                    if(resourceJarPath.contains(":")) {
                        resourceJarPath = resourceJarPath.substring(resourceJarPath.lastIndexOf(":") + 1);
                    }
                    if(resourceJarPath.contains("!")) {
                        resourceJarPath = resourceJarPath.substring(0, resourceJarPath.indexOf("!"));
                    }
                    File resourceJar = new File(resourceJarPath);

                    // Dump the closure lib from classpath.
                    dumpJar(resourceJar, closureLibDir);
                }
                // The compiler automatically adds a "closure" to the lib dir path,
                // so we omit this here.
                closureLibDirPath = intermediateDir.getParentFile().getPath();
            }
            // Fallback to the default.
            else {
                closureLibDirPath = ((JSGoogConfiguration) configuration).getClosureLib();
            }
        }

        // Dump FlexJS to the target directory.
        @SuppressWarnings("unused")
		String flexJsLibDirPath;
        // Check if the "FlexJS/src/createjs_externals.js" is available in the classpath.
        URL resource = Thread.currentThread().getContextClassLoader().getResource("FlexJS/src/createjs_externals.js");
        if(resource != null) {
            File flexJsLibDir = new File(intermediateDir.getParent(), "flexjs");

            // Only create and dump the content, if the directory does not exists.
            if(!flexJsLibDir.exists()) {
                if(!flexJsLibDir.mkdirs()) {
                    throw new IOException(
                            "Unable to create directory for flexjs-lib at " + flexJsLibDir.getAbsolutePath());
                }

                // Strip the url of the parts we don't need.
                // Unless we are not using some insanely complex setup
                // the resource will always be on the same machine.
                String resourceJarPath = resource.getFile();
                if(resourceJarPath.contains(":")) {
                    resourceJarPath = resourceJarPath.substring(resourceJarPath.lastIndexOf(":") + 1);
                }
                if(resourceJarPath.contains("!")) {
                    resourceJarPath = resourceJarPath.substring(0, resourceJarPath.indexOf("!"));
                }
                File resourceJar = new File(resourceJarPath);

                // Dump the closure lib from classpath.
                dumpJar(resourceJar, flexJsLibDir);
            }
            // The compiler automatically adds a "closure" to the lib dir path,
            // so we omit this here.
            flexJsLibDirPath = intermediateDir.getParentFile().getPath();
        }

        final String closureGoogSrcLibDirPath = closureLibDirPath
                + "/closure/goog/";
        final String closureGoogTgtLibDirPath = intermediateDirPath
                + "/library/closure/goog";
        //final String depsSrcFilePath = intermediateDirPath
        //        + "/library/closure/goog/deps.js";
        @SuppressWarnings("unused")
		final String depsTgtFilePath = intermediateDirPath + "/deps.js";
        final String projectIntermediateJSFilePath = intermediateDirPath
                + File.separator + outputFileName;
        final String projectReleaseJSFilePath = releaseDirPath
                + File.separator + outputFileName;

        appendExportSymbol(projectIntermediateJSFilePath, projectName);
        appendEncodedCSS(projectIntermediateJSFilePath, projectName);
        appendLanguage(projectIntermediateJSFilePath, projectName);

        //if (!subsetGoog)
        //{
            // (erikdebruin) We need to leave the 'goog' files and dependencies well
            //               enough alone. We copy the entire library over so the 
            //               'goog' dependencies will resolve without our help.
            FileUtils.copyDirectory(new File(closureGoogSrcLibDirPath), new File(closureGoogTgtLibDirPath));
        //}
        
        JSClosureCompilerWrapper compilerWrapper = new JSClosureCompilerWrapper();

        GoogDepsWriter gdw = new GoogDepsWriter(intermediateDir, projectName, 
        		(JSGoogConfiguration) configuration, project.getLibraries());
        StringBuilder depsFileData = new StringBuilder();
        try
        {
        	ArrayList<String> fileList = gdw.getListOfFiles(problems);
        	for (String file : fileList)
        	{
                compilerWrapper.addJSSourceFile(file);	
        	}
            ok = gdw.generateDeps(problems, depsFileData);
        	/*
            if (!subsetGoog)
            {
                writeFile(depsTgtFilePath, depsFileData.toString(), false); 
            }
            else
            {
                String s = depsFileData.toString();
                int c = s.indexOf("'goog.");
                ArrayList<String> googreqs = new ArrayList<String>();
                while (c != -1)
                {
                    int c2 = s.indexOf("'", c + 1);
                    String googreq = s.substring(c, c2 + 1);
                    googreqs.add(googreq);
                    c = s.indexOf("'goog.", c2);
                }
                HashMap<String, DependencyRecord> defmap = new HashMap<String, DependencyRecord>();
                // read in goog's deps.js
                FileInputStream fis = new FileInputStream(closureGoogSrcLibDirPath + "/deps.js");
                Scanner scanner = new Scanner(fis, "UTF-8");
                String addDependency = "goog.addDependency('";
                int currentLine = 0;
                while (scanner.hasNextLine())
                {
                    String googline = scanner.nextLine();
                    if (googline.indexOf(addDependency) == 0)
                    {
                        int c1 = googline.indexOf("'", addDependency.length() + 1);
                        String googpath = googline.substring(addDependency.length(), c1);
                        String googdefs = googline.substring(googline.indexOf("[") + 1, googline.indexOf("]"));
                        String googdeps = googline.substring(googline.lastIndexOf("[") + 1, googline.lastIndexOf("]"));
                        String[] thedefs = googdefs.split(",");
                        DependencyRecord deprec = new DependencyRecord();
                        deprec.path = googpath;
                        deprec.deps = googdeps;
                        deprec.line = googline;
                        deprec.lineNumber = currentLine;
                        for (String def : thedefs)
                        {
                            def = def.trim();
                            defmap.put(def, deprec);
                        }
                    }
                    currentLine++;
                }
                // (erikdebruin) Prevent 'Resource leak' warning on line 212:
                scanner.close();      
                ArrayList<DependencyRecord> subsetdeps = new ArrayList<DependencyRecord>();
                HashMap<String, String> gotgoog = new HashMap<String, String>();
                for (String req : googreqs)
                {
                    DependencyRecord deprec = defmap.get(req);
                    // if we've already processed this file, skip
                    if (!gotgoog.containsKey(deprec.path))
                    {
                        gotgoog.put(deprec.path, null);
                        subsetdeps.add(deprec);
                        addDeps(subsetdeps, gotgoog, defmap, deprec.deps);                        
                    }
                }
                // now we should have the subset of files we need in the order needed
                StringBuilder sb = new StringBuilder();
                sb.append("goog.addDependency('base.js', ['goog'], []);\n");
                File file = new File(closureGoogSrcLibDirPath + "/base.js");
                FileUtils.copyFileToDirectory(file, new File(closureGoogTgtLibDirPath));
                compilerWrapper.addJSSourceFile(file.getCanonicalPath());
                Collections.sort(subsetdeps, new DependencyLineComparator());
                for (DependencyRecord subsetdeprec : subsetdeps)
                {
                    sb.append(subsetdeprec.line).append("\n");
                }
                writeFile(depsTgtFilePath, sb.toString() + depsFileData.toString(), false);
                // copy the required files
                for (String googfn : gotgoog.keySet())
                {
                    file = new File(closureGoogSrcLibDirPath + File.separator + googfn);
                    String dir = closureGoogTgtLibDirPath;
                    if (googfn.contains("/"))
                    {
                        dir += File.separator + googfn.substring(0, googfn.lastIndexOf("/"));
                    }
                    FileUtils.copyFileToDirectory(file, new File(dir));
                    compilerWrapper.addJSSourceFile(file.getCanonicalPath());
                }
            }*/
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
            return false;
        }
        
        IOFileFilter pngSuffixFilter = FileFilterUtils.and(FileFileFilter.FILE,
                FileFilterUtils.suffixFileFilter(".png"));
        IOFileFilter gifSuffixFilter = FileFilterUtils.and(FileFileFilter.FILE,
                FileFilterUtils.suffixFileFilter(".gif"));
        IOFileFilter jpgSuffixFilter = FileFilterUtils.and(FileFileFilter.FILE,
                FileFilterUtils.suffixFileFilter(".jpg"));
        IOFileFilter jsonSuffixFilter = FileFilterUtils.and(FileFileFilter.FILE,
                FileFilterUtils.suffixFileFilter(".json"));
        IOFileFilter assetFiles = FileFilterUtils.or(pngSuffixFilter,
                jpgSuffixFilter, gifSuffixFilter, jsonSuffixFilter);
        IOFileFilter subdirs = FileFilterUtils.or(DirectoryFileFilter.DIRECTORY, assetFiles);

        FileUtils.copyDirectory(srcDir, intermediateDir, subdirs);
        FileUtils.copyDirectory(srcDir, releaseDir, subdirs);

        //File srcDeps = new File(depsSrcFilePath);

        writeHTML("intermediate", projectName, intermediateDirPath, depsFileData.toString(), gdw.additionalHTML);
        writeHTML("release", projectName, releaseDirPath, null, gdw.additionalHTML);
        writeCSS(projectName, intermediateDirPath);
        writeCSS(projectName, releaseDirPath);

        /*
        if (!subsetGoog)
        {
            // (erikdebruin) add 'goog' files
            Collection<File> files = org.apache.commons.io.FileUtils.listFiles(new File(
                    closureGoogTgtLibDirPath), new RegexFileFilter("^.*(\\.js)"),
                    DirectoryFileFilter.DIRECTORY);
            for (File file : files)
            {
                compilerWrapper.addJSSourceFile(file.getCanonicalPath());
            }
        }
        */
        Collection<File> files = org.apache.commons.io.FileUtils.listFiles(new File(
        		closureGoogSrcLibDirPath), new RegexFileFilter("^.*(\\.js)"),
                DirectoryFileFilter.DIRECTORY);
        for (File file : files)
        {
            compilerWrapper.addJSSourceFile(file.getCanonicalPath());
        }
        
        /*
        // (erikdebruin) add project files
        for (String filePath : gdw.filePathsInOrder)
        {
            compilerWrapper.addJSSourceFile(
                    new File(filePath).getCanonicalPath());   
        }
        */
        
        compilerWrapper.setOptions(
                projectReleaseJSFilePath, useStrictPublishing, projectName);
        
        /*
        // (erikdebruin) Include the 'goog' deps to allow the compiler to resolve
        //               dependencies.
        compilerWrapper.addJSSourceFile(
                closureGoogSrcLibDirPath + File.separator + "deps.js");
        */
        List<String> externs = ((JSGoogConfiguration)configuration).getExternalJSLib();
        for (String extern : externs)
        {
            compilerWrapper.addJSExternsFile(extern);
        }
        
        compilerWrapper.targetFilePath = projectReleaseJSFilePath;
        compilerWrapper.compile();
        
        appendSourceMapLocation(projectReleaseJSFilePath, projectName);

        /*
        if (!isMarmotinniRun)
        {
            String allDeps = "";
            if (!subsetGoog) {
                allDeps += FileUtils.readFileToString(srcDeps);
            }
            allDeps += FileUtils.readFileToString(new File(depsTgtFilePath));
            
            FileUtils.writeStringToFile(srcDeps, allDeps);
            
            org.apache.commons.io.FileUtils.deleteQuietly(new File(depsTgtFilePath));
        }
		*/
        
        //if (ok)
            System.out.println("The project '"
                + projectName
                + "' has been successfully compiled and optimized.");
        
        return true;
    }

    /*
    private void addDeps(ArrayList<DependencyRecord> subsetdeps, HashMap<String, String> gotgoog, 
                            HashMap<String, DependencyRecord> defmap, String deps)
    {
        if (deps.length() == 0) {
            return;
        }
        
        String[] deplist = deps.split(",");
        for (String dep : deplist)
        {
            dep = dep.trim();
            DependencyRecord deprec = defmap.get(dep);
            if (!gotgoog.containsKey(deprec.path))
            {
                gotgoog.put(deprec.path, null);
                // put addDependencyLine in subset file
                subsetdeps.add(deprec);
                addDeps(subsetdeps, gotgoog, defmap, deprec.deps);                        
            }
        }
    }*/
    
    private void appendExportSymbol(String path, String projectName)
            throws IOException
    {
        writeFile(path, "\n\n// Ensures the symbol will be visible after compiler renaming.\n" +
                "goog.exportSymbol('" + projectName + "', " + projectName + ");\n", true);
    }

    private void appendEncodedCSS(String path, String projectName)
            throws IOException
    {
        StringBuilder appendString = new StringBuilder();
        appendString.append("\n\n");
        appendString.append(projectName);
        appendString.append(".prototype.cssData = [");
        JSCSSCompilationSession cssSession = (JSCSSCompilationSession) project.getCSSCompilationSession();
        String s = cssSession.getEncodedCSS();
        int reqidx = s.indexOf(JSGoogEmitterTokens.GOOG_REQUIRE.getToken());
        if (reqidx != -1)
        {
            String reqs = s.substring(reqidx);
            s = s.substring(0, reqidx - 1);
            String fileData = readCode(new File(path));
            reqidx = fileData.indexOf(JSGoogEmitterTokens.GOOG_REQUIRE.getToken());
            String after = fileData.substring(reqidx);
            String before = fileData.substring(0, reqidx - 1);
            s = before + reqs + after + appendString.toString() + s;
            writeFile(path, s, false);
        }
        else
        {
            appendString.append(s);
            writeFile(path, appendString.toString(), true);
        }
    }
     
    private void appendLanguage(String path, String projectName)
    	throws IOException
	{
		StringBuilder appendString = new StringBuilder();
		appendString.append(JSGoogEmitterTokens.GOOG_REQUIRE.getToken());
		appendString.append(ASEmitterTokens.PAREN_OPEN.getToken());
		appendString.append(ASEmitterTokens.SINGLE_QUOTE.getToken());
		appendString.append(JSFlexJSEmitterTokens.LANGUAGE_QNAME.getToken());
		appendString.append(ASEmitterTokens.SINGLE_QUOTE.getToken());
		appendString.append(ASEmitterTokens.PAREN_CLOSE.getToken());
		appendString.append(ASEmitterTokens.SEMICOLON.getToken());
		appendString.append(File.separator);
		
	    String fileData = readCode(new File(path));
	    int reqidx = fileData.indexOf(appendString.toString());
	    if (reqidx == -1 && project.needLanguage)
	    {
	    	reqidx = fileData.lastIndexOf(JSGoogEmitterTokens.GOOG_REQUIRE.getToken());
	    	if (reqidx == -1)
	    		reqidx = fileData.lastIndexOf(JSGoogEmitterTokens.GOOG_PROVIDE.getToken());
	    	reqidx = fileData.indexOf(";", reqidx);
		    String after = fileData.substring(reqidx + 1);
		    String before = fileData.substring(0, reqidx + 1);
		    String s = before + "\n" + appendString.toString() + after;
		    writeFile(path, s, false);
	    }
	}

    protected String readCode(File file)
    {
        String code = "";
        try
        {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF8"));

            String line = in.readLine();

            while (line != null)
            {
                code += line + "\n";
                line = in.readLine();
            }
            code = code.substring(0, code.length() - 1);

            in.close();
        }
        catch (Exception e)
        {
            // nothing to see, move along...
        }

        return code;
    }

    private void writeHTML(String type, String projectName, String dirPath, String deps, List<String> additionalHTML)
            throws IOException
    {
        StringBuilder htmlFile = new StringBuilder();
        htmlFile.append("<!DOCTYPE html>\n");
        htmlFile.append("<html>\n");
        htmlFile.append("<head>\n");
        htmlFile.append("\t<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge,chrome=1\">\n");
        htmlFile.append("\t<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n");
        htmlFile.append("\t<link rel=\"stylesheet\" type=\"text/css\" href=\"").append(projectName).append(".css\">\n");

        for (String s : additionalHTML)
            htmlFile.append(s).append("\n");
        
        if ("intermediate".equals(type))
        {
            htmlFile.append("\t<script type=\"text/javascript\" src=\"./library/closure/goog/base.js\"></script>\n");
            htmlFile.append("\t<script type=\"text/javascript\">\n");
            htmlFile.append(deps);
            htmlFile.append("\t\tgoog.require(\"");
            htmlFile.append(projectName);
            htmlFile.append("\");\n");
            htmlFile.append("\t</script>\n");
        }
        else
        {
            htmlFile.append("\t<script type=\"text/javascript\" src=\"./");
            htmlFile.append(projectName);
            htmlFile.append(".js\"></script>\n");
        }

        htmlFile.append("</head>\n");
        htmlFile.append("<body>\n");
        htmlFile.append("\t<script type=\"text/javascript\">\n");
        htmlFile.append("\t\tnew ");
        htmlFile.append(projectName);
        htmlFile.append("()");
        htmlFile.append(".start();\n");
        htmlFile.append("\t</script>\n");
        htmlFile.append("</body>\n");
        htmlFile.append("</html>");

        writeFile(dirPath + File.separator + "index.html", htmlFile.toString(),
                false);
    }

    private void writeCSS(String projectName, String dirPath)
            throws IOException
    {
        JSCSSCompilationSession cssSession = (JSCSSCompilationSession) project.getCSSCompilationSession();
        writeFile(dirPath + File.separator + projectName + ".css",
                cssSession.emitCSS(), false);
    }
}
