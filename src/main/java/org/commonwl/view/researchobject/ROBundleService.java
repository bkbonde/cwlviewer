/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.commonwl.view.researchobject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.taverna.robundle.Bundle;
import org.apache.taverna.robundle.Bundles;
import org.apache.taverna.robundle.manifest.*;
import org.commonwl.view.cwl.CWLTool;
import org.commonwl.view.cwl.CWLValidationException;
import org.commonwl.view.git.GitDetails;
import org.commonwl.view.git.GitType;
import org.commonwl.view.graphviz.GraphVizService;
import org.commonwl.view.workflow.Workflow;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.io.FileUtils.readFileToString;

/**
 * Service handling Research Object Bundles
 */
@Service
public class ROBundleService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    // Services
    private GraphVizService graphVizService;
    private CWLTool cwlTool;

    // Configuration variables
    private Agent appAgent;
    private int singleFileSizeLimit;
    private Path bundleStorage;

    // Pattern for extracting version from a cwl file
    private final String CWL_VERSION_REGEX = "cwlVersion:\\s*\"?(?:cwl:)?([^\\s\"]+)\"?";
    private final Pattern cwlVersionPattern = Pattern.compile(CWL_VERSION_REGEX);

    /**
     * Creates an instance of this service which handles Research Object Bundles
     * @param bundleStorage The configured storage location for bundles
     * @param appName The name of the application from properties, for attribution
     * @param appURL The URL of the application from properties, for attribution
     * @param singleFileSizeLimit The file size limit for each file in the RO bundle
     * @throws URISyntaxException Error in creating URI for appURL
     */
    @Autowired
    public ROBundleService(@Value("${bundleStorage}") Path bundleStorage,
                           @Value("${applicationName}") String appName,
                           @Value("${applicationURL}") String appURL,
                           @Value("${singleFileSizeLimit}") int singleFileSizeLimit,
                           GraphVizService graphVizService,
                           CWLTool cwlTool) throws URISyntaxException {
        this.bundleStorage = bundleStorage;
        this.appAgent = new Agent(appName);
        appAgent.setUri(new URI(appURL));
        this.singleFileSizeLimit = singleFileSizeLimit;
        this.graphVizService = graphVizService;
        this.cwlTool = cwlTool;
    }

    /**
     * Creates a new research object bundle for a workflow from a Git repository
     * @param workflow The workflow to create the research object for
     * @return The constructed bundle
     */
    public Bundle createBundle(Workflow workflow, GitDetails gitInfo) throws IOException {

        // Create a new RO bundle
        Bundle bundle = Bundles.createBundle();
        Manifest manifest = bundle.getManifest();

        // Simplified attribution for RO bundle
        try {
            // Tool attribution in createdBy
            manifest.setCreatedBy(appAgent);

            // Retrieval Info
            // TODO: Make this importedBy/On/From
            manifest.setRetrievedBy(appAgent);
            manifest.setRetrievedOn(manifest.getCreatedOn());
            manifest.setRetrievedFrom(new URI(gitInfo.getUrl()));

            // Make a directory in the RO bundle to store the files
            Path bundleRoot = bundle.getRoot();
            Path bundlePath = bundleRoot.resolve("workflow");
            Files.createDirectory(bundlePath);

            // Add the files from the Github repo to this workflow
            Set<HashableAgent> authors = new HashSet<>();
            Git gitRepo = Git.open(new File(workflow.getGitRepoPath()));
            Path relativePath = Paths.get(FilenameUtils.getPath(gitInfo.getPath()));
            Path gitPath = gitRepo.getRepository().getWorkTree().toPath().resolve(relativePath);
            addFilesToBundle(gitInfo, bundle, bundlePath, gitRepo, gitPath, authors);

            // Add combined authors
            manifest.setAuthoredBy(new ArrayList<>(authors));

            // Add visualisation images
            File png = graphVizService.getGraph(workflow.getID() + ".png", workflow.getVisualisationDot(), "png");
            Files.copy(png.toPath(), bundleRoot.resolve("visualisation.png"));
            PathMetadata pngAggr = bundle.getManifest().getAggregation(bundleRoot.resolve("visualisation.png"));
            pngAggr.setRetrievedFrom(new URI(appAgent.getUri() + workflow.getVisualisationPng()));

            File svg = graphVizService.getGraph(workflow.getID() + ".svg", workflow.getVisualisationDot(), "svg");
            Files.copy(svg.toPath(), bundleRoot.resolve("visualisation.svg"));
            PathMetadata svgAggr = bundle.getManifest().getAggregation(bundleRoot.resolve("visualisation.svg"));
            svgAggr.setRetrievedFrom(new URI(appAgent.getUri() + workflow.getVisualisationSvg()));

            // Add annotation files
            GitDetails wfDetails = workflow.getRetrievedFrom();

            // Get URL to run cwltool
            String rawUrl = wfDetails.getRawUrl();
            String packedWorkflowID = workflow.getPackedWorkflowID();
            if (packedWorkflowID != null) {
                if (packedWorkflowID.charAt(0) != '#') {
                    rawUrl += "#";
                }
                rawUrl += packedWorkflowID;
            }

            // Run cwltool for annotations
            List<PathAnnotation> manifestAnnotations = new ArrayList<>();
            try {
                addAggregation(bundle, manifestAnnotations,
                        "merged.cwl", cwlTool.getPackedVersion(rawUrl));
            } catch (CWLValidationException ex) {
                logger.error("Could not pack workflow when creating Research Object", ex.getMessage());
            }
            if (gitInfo.getType() != GitType.GENERIC) {
                try {
                    addAggregation(bundle, manifestAnnotations,
                            "workflow.ttl", cwlTool.getRDF(rawUrl));
                } catch (CWLValidationException ex) {
                    logger.error("Could not get RDF for workflow from raw URL", ex.getMessage());
                }
            }
            bundle.getManifest().setAnnotations(manifestAnnotations);

            // Git2prov history
            List<Path> history = new ArrayList<>();
            history.add(Paths.get("http://git2prov.org/git2prov?giturl=" +
                    gitInfo.getRepoUrl() + "&serialization=PROV-JSON"));
            bundle.getManifest().setHistory(history);

        } catch (URISyntaxException ex) {
            logger.error("Error creating URI for RO Bundle", ex);
        }

        // Return the completed bundle
        return bundle;

    }

    /**
     * Add files to this bundle from a list of Github repository contents
     * @param gitDetails The Git information for the repository
     * @param bundle The RO bundle to add files/directories to
     * @param bundlePath The current path within the RO bundle
     * @param gitRepo The Git repository
     * @param repoPath The current path within the Git repository
     * @param authors The combined set of authors for al the files
     */
    private void addFilesToBundle(GitDetails gitDetails, Bundle bundle, Path bundlePath,
                                  Git gitRepo, Path repoPath, Set<HashableAgent> authors)
            throws IOException {
        File[] files = repoPath.toFile().listFiles();
        for (File file : files) {
            if (!file.getName().equals(".git")) {
                if (file.isDirectory()) {

                    // Create a new folder in the RO for this directory
                    Path newBundlePath = bundlePath.resolve(file.getName());
                    Files.createDirectory(newBundlePath);

                    // Create git details object for subfolder
                    GitDetails subfolderGitDetails = new GitDetails(gitDetails.getRepoUrl(), gitDetails.getBranch(),
                            Paths.get(gitDetails.getPath()).resolve(file.getName()).toString());

                    // Add all files in the subdirectory to this new folder
                    addFilesToBundle(subfolderGitDetails, bundle, newBundlePath, gitRepo,
                            repoPath.resolve(file.getName()), authors);

                } else {
                    try {
                        // Where to store the new file in bundle
                        Path bundleFilePath = bundlePath.resolve(file.getName());

                        // Create git details object for file
                        GitDetails fileGitDetails = new GitDetails(gitDetails.getRepoUrl(), gitDetails.getBranch(),
                                Paths.get(gitDetails.getPath()).resolve(file.getName()).toString());

                        // Get direct URL
                        URI rawURI = new URI(fileGitDetails.getRawUrl());

                        // Variable to store file contents and aggregation
                        String fileContent = null;
                        PathMetadata aggregation;

                        // Download or externally link if oversized
                        if (file.length() <= singleFileSizeLimit) {
                            // Save file to research object bundle
                            fileContent = readFileToString(file);
                            Bundles.setStringValue(bundleFilePath, fileContent);

                            // Set retrieved information for this file in the manifest
                            aggregation = bundle.getManifest().getAggregation(bundleFilePath);
                            if (gitDetails.getType() != GitType.GENERIC) {
                                aggregation.setRetrievedFrom(rawURI);
                                aggregation.setRetrievedBy(appAgent);
                                aggregation.setRetrievedOn(aggregation.getCreatedOn());
                            }
                        } else {
                            logger.info("File " + file.getName() + " is too large to download - " +
                                    FileUtils.byteCountToDisplaySize(file.length()) + "/" +
                                    FileUtils.byteCountToDisplaySize(singleFileSizeLimit) +
                                    ", linking externally to RO bundle");

                            // Set information for this file in the manifest
                            aggregation = bundle.getManifest().getAggregation(rawURI);
                            Proxy bundledAs = new Proxy();
                            bundledAs.setURI();
                            bundledAs.setFolder(repoPath);
                            aggregation.setBundledAs(bundledAs);
                        }

                        // Special handling for cwl files
                        if (FilenameUtils.getExtension(file.getName()).equals("cwl")) {
                            // Correct mime type (no official standard for yaml)
                            aggregation.setMediatype("text/x-yaml");

                            // Add conformsTo for version extracted from regex
                            if (fileContent != null) {
                                Matcher m = cwlVersionPattern.matcher(fileContent);
                                if (m.find()) {
                                    aggregation.setConformsTo(new URI("https://w3id.org/cwl/" + m.group(1)));
                                }
                            }
                        }

                        // Add authors from git commits to the file
                        try {
                            Set<HashableAgent> fileAuthors = new HashSet<>();
                            Iterable<RevCommit> logs = gitRepo.log()
                                    .addPath(Paths.get(gitDetails.getPath()).resolve(file.getName()).toString())
                                    .call();
                            for (RevCommit rev : logs) {
                                // Use author first with backup of committer
                                PersonIdent author = rev.getAuthorIdent();
                                if (author == null) {
                                    author = rev.getCommitterIdent();
                                }
                                // Create a new agent and add as much detail as possible
                                if (author != null) {
                                    HashableAgent newAgent = new HashableAgent();
                                    String name = author.getName();
                                    if (name != null && name.length() > 0) {
                                        newAgent.setName(author.getName());
                                    }
                                    String email = author.getEmailAddress();
                                    if (email != null && email.length() > 0) {
                                        newAgent.setUri(new URI("mailto:" + author.getEmailAddress()));
                                    }
                                    fileAuthors.add(newAgent);
                                }
                            }
                            authors.addAll(fileAuthors);
                            aggregation.setAuthoredBy(new ArrayList<>(fileAuthors));
                        } catch (GitAPIException ex) {
                            logger.error("Could not get commits for file " + repoPath, ex);
                        }

                        // Set retrieved information for this file in the manifest
                        aggregation.setRetrievedFrom(rawURI);
                        aggregation.setRetrievedBy(appAgent);
                        aggregation.setRetrievedOn(aggregation.getCreatedOn());

                    } catch (URISyntaxException ex) {
                        logger.error("Error creating URI for RO Bundle", ex);
                    }
                }
            }
        }
    }

    /**
     * Save the Research Object Bundle to disk
     * @param roBundle The bundle to be saved
     * @return The path to the research object
     * @throws IOException Any errors in saving
     */
    public Path saveToFile(Bundle roBundle) throws IOException {
        String fileName = "bundle-" + java.util.UUID.randomUUID() + ".zip";
        Path bundleLocation = Files.createFile(bundleStorage.resolve(fileName));
        Bundles.closeAndSaveBundle(roBundle, bundleLocation);
        return bundleLocation;
    }

    /**
     * Add an aggregation to the Research Object Bundle
     * @param roBundle The bundle to add to
     * @param fileName The file name of the aggregation
     * @param manifestAnnotations The list of manifest aggregations
     * @param content The identifier for the resource containing the
     *                body of the annotation
     * @throws IOException Errors accessing the bundle
     */
    private void addAggregation(Bundle roBundle,
                                List<PathAnnotation> manifestAnnotations,
                                String fileName,
                                String content) throws IOException {
        Path annotations = Bundles.getAnnotations(roBundle);
        Path packedPath = annotations.resolve(fileName);
        Bundles.setStringValue(packedPath, content);
        PathAnnotation packedFile = new PathAnnotation();
        packedFile.setContent(packedPath);
        packedFile.setAbout(roBundle.getManifest().getId());
        packedFile.generateAnnotationId();
        manifestAnnotations.add(packedFile);
    }

}
