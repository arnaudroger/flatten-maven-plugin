package org.codehaus.mojo.flatten;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Activation;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileInjector;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependencies.resolve.DependencyResolver;
import org.codehaus.mojo.flatten.cifriendly.CiInterpolator;
import org.codehaus.mojo.flatten.model.resolution.FlattenModelResolver;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * This MOJO realizes the goal <code>flatten</code> that generates the flattened POM and {@link #isUpdatePomFile()
 * potentially updates the POM file} so that the current {@link MavenProject}'s {@link MavenProject#getFile() file}
 * points to the flattened POM instead of the original <code>pom.xml</code> file. The flattened POM is a reduced version
 * of the original POM with the focus to contain only the important information for consuming it. Therefore information
 * that is only required for maintenance by developers and to build the project artifact(s) are stripped. Starting from
 * here we specify how the flattened POM is created from the original POM and its project:<br>
 * <table border="1" summary="">
 * <tr>
 * <th>Element</th>
 * <th>Transformation</th>
 * <th>Note</th>
 * </tr>
 * <tr>
 * <td>{@link Model#getModelVersion() modelVersion}</td>
 * <td>Fixed to "4.0.0"</td>
 * <td>New maven versions will once be able to evolve the model version without incompatibility to older versions if
 * flattened POMs get deployed.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getGroupId() groupId}<br>
 * {@link Model#getArtifactId() artifactId}<br>
 * {@link Model#getVersion() version}<br>
 * {@link Model#getPackaging() packaging}</td>
 * <td>resolved</td>
 * <td>copied to the flattened POM but with inheritance from {@link Model#getParent() parent} as well as with all
 * variables and defaults resolved. These elements are technically required for consumption.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getLicenses() licenses}</td>
 * <td>resolved</td>
 * <td>copied to the flattened POM but with inheritance from {@link Model#getParent() parent} as well as with all
 * variables and defaults resolved. The licenses would not be required in flattened POM. However, they make sense for
 * publication and deployment and are important for consumers of your artifact.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getDependencies() dependencies}</td>
 * <td>resolved specially</td>
 * <td>flattened POM contains the actual dependencies of the project. Test dependencies are removed. Variables and
 * {@link Model#getDependencyManagement() dependencyManagement} is resolved to get fixed dependency attributes
 * (especially {@link Dependency#getVersion() version}). If {@link #isEmbedBuildProfileDependencies()
 * embedBuildProfileDependencies} is set to <code>true</code>, then also build-time driven {@link Profile}s will be
 * evaluated and may add {@link Dependency dependencies}. For further details see {@link Profile}s below.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getProfiles() profiles}</td>
 * <td>resolved specially</td>
 * <td>only the {@link Activation} and the {@link Dependency dependencies} of a {@link Profile} are copied to the
 * flattened POM. If you set the parameter {@link #isEmbedBuildProfileDependencies() embedBuildProfileDependencies} to
 * <code>true</code> then only profiles {@link Activation activated} by {@link Activation#getJdk() JDK} or
 * {@link Activation#getOs() OS} will be added to the flattened POM while the other profiles are triggered by the
 * current build setup and if activated their impact on dependencies is embedded into the resulting flattened POM.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getName() name}<br>
 * {@link Model#getDescription() description}<br>
 * {@link Model#getUrl() url}<br>
 * {@link Model#getInceptionYear() inceptionYear}<br>
 * {@link Model#getOrganization() organization}<br>
 * {@link Model#getScm() scm}<br>
 * {@link Model#getDevelopers() developers}<br>
 * {@link Model#getContributors() contributors}<br>
 * {@link Model#getMailingLists() mailingLists}<br>
 * {@link Model#getPluginRepositories() pluginRepositories}<br>
 * {@link Model#getIssueManagement() issueManagement}<br>
 * {@link Model#getCiManagement() ciManagement}<br>
 * {@link Model#getDistributionManagement() distributionManagement}</td>
 * <td>configurable</td>
 * <td>Will be stripped from the flattened POM by default. You can configure all of the listed elements inside
 * <code>pomElements</code> that should be kept in the flattened POM (e.g. {@literal
 * <pomElements><name/><description/><developers/><contributors/></pomElements>}). For common use-cases there are
 * predefined modes available via the parameter <code>flattenMode</code> that should be used in preference.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getPrerequisites() prerequisites}</td>
 * <td>configurable</td>
 * <td>Like above but by default NOT removed if packaging is "maven-plugin".</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getRepositories() repositories}</td>
 * <td>configurable</td>
 * <td>Like two above but by default NOT removed. If you want have it removed, you need to use the parameter
 * <code>pomElements</code> and configure the child element <code>repositories</code> with value <code>flatten</code>.
 * </td>
 * </tr>
 * <tr>
 * <td>{@link Model#getParent() parent}<br>
 * {@link Model#getBuild() build}<br>
 * {@link Model#getDependencyManagement() dependencyManagement}<br>
 * {@link Model#getProperties() properties}<br>
 * {@link Model#getModules() modules}<br>
 * {@link Model#getReporting() reporting}</td>
 * <td>configurable</td>
 * <td>These elements should typically be completely stripped from the flattened POM. However for ultimate flexibility
 * (e.g. if you only want to resolve variables in a POM with packaging pom) you can also configure to keep these
 * elements. We strictly recommend to use this feature with extreme care and only if packaging is pom (for "Bill of
 * Materials"). In the latter case you configure the parameter <code>flattenMode</code> to the value
 * <code>bom</code>.<br>
 * If the <code>build</code> element contains plugins in the <code>build/plugins</code> section which are configured to
 * load <a href="http://maven.apache.org/pom.html#Extensions">extensions</a>, a reduced <code>build</code> element
 * containing these plugins will be kept in the flattened pom.</td>
 * </tr>
 * </table>
 *
 * @author Joerg Hohwiller (hohwille at users.sourceforge.net)
 */
@SuppressWarnings( "deprecation" )
// CHECKSTYLE_OFF: LineLength
@Mojo( name = "flatten", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", requiresDependencyCollection = ResolutionScope.RUNTIME, threadSafe = true )
// CHECKSTYLE_ON: LineLength
public class FlattenMojo
    extends AbstractFlattenMojo
{

    private static final int INITIAL_POM_WRITER_SIZE = 4096;

    /**
     * The Maven Project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * The flag to indicate if the generated flattened POM shall be set as POM file to the current project. By default
     * this is only done for projects with packaging other than <code>pom</code>. You may want to also do this for
     * <code>pom</code> packages projects by setting this parameter to <code>true</code> or you can use
     * <code>false</code> in order to only generate the flattened POM but never set it as POM file. If
     * <code>flattenMode</code> is set to bom the default value will be <code>true</code>.
     */
    @Parameter( property = "updatePomFile" )
    private Boolean updatePomFile;

    /** The {@link ArtifactRepository} required to resolve POM using {@link #modelBuilder}. */
    @Parameter( defaultValue = "${localRepository}", readonly = true, required = true )
    private ArtifactRepository localRepository;

    /**
     * Profiles activated by OS or JDK are valid ways to have different dependencies per environment. However, profiles
     * activated by property of file are less clear. When setting this parameter to <code>true</code>, the latter
     * dependencies will be written as direct dependencies of the project. <strong>This is not how Maven2 and Maven3
     * handles dependencies</strong>. When keeping this property <code>false</code>, all profiles will stay in the
     * flattened-pom.
     */
    @Parameter( defaultValue = "false" )
    private Boolean embedBuildProfileDependencies;

    @Parameter( defaultValue = "false" )
    private Boolean embedAllProfileDependencies;
    /**
     * The {@link MojoExecution} used to get access to the raw configuration of {@link #pomElements} as empty tags are
     * mapped to null.
     */
    @Parameter( defaultValue = "${mojo}", readonly = true, required = true )
    private MojoExecution mojoExecution;

    /**
     * The {@link Model} that defines how to handle additional POM elements. Please use <code>flattenMode</code> in
     * preference if possible. This parameter is only for ultimate flexibility.
     */
    @Parameter( required = false )
    private FlattenDescriptor pomElements;

    /**
     * The different possible values for flattenMode:
     * <table border="1" summary="">
     * <thead>
     * <tr>
     * <td>Mode</td>
     * <td>Description</td>
     * </tr>
     * </thead> <tbody>
     * <tr>
     * <td>oss</td>
     * <td>For Open-Source-Software projects that want to keep all {@link FlattenDescriptor optional POM elements}
     * except for {@link Model#getRepositories() repositories} and {@link Model#getPluginRepositories()
     * pluginRepositories}.</td>
     * <tr>
     * <td>ossrh</td>
     * <td>Keeps all {@link FlattenDescriptor optional POM elements} that are required for
     * <a href="https://docs.sonatype.org/display/Repository/Sonatype+OSS+Maven+Repository+Usage+Guide">OSS
     * Repository-Hosting</a>.</td>
     * </tr>
     * <tr>
     * <td>bom</td>
     * <td>Like {@link #ossrh} but additionally keeps {@link Model#getDependencyManagement() dependencyManagement} and
     * {@link Model#getProperties() properties}. Especially it will keep the {@link Model#getDependencyManagement()
     * dependencyManagement} <em>as-is</em> without resolving parent influences and import-scoped dependencies. This is
     * useful if your POM represents a <a href=
     * "http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Importing_Dependencies"
     * >BOM (Bill Of Material)</a> and you do not want to deploy it as is (to remove parent and resolve version
     * variables, etc.).</td>
     * </tr>
     * <tr>
     * <td>defaults</td>
     * <td>The default mode that removes all {@link FlattenDescriptor optional POM elements} except
     * {@link Model#getRepositories() repositories}.</td>
     * </tr>
     * <tr>
     * <td>clean</td>
     * <td>Removes all {@link FlattenDescriptor optional POM elements}.</td>
     * </tr>
     * <tr>
     * <td>fatjar</td>
     * <td>Removes all {@link FlattenDescriptor optional POM elements} and all {@link Model#getDependencies()
     * dependencies}.</td>
     * </tr>
     * <tr>
     * <td>resolveCiFriendliesOnly</td>
     * <td>Only resolves variables revision, sha1 and changelist. Keeps everything else. 
	 * See <a href="https://maven.apache.org/maven-ci-friendly.html">Maven CI Friendly</a> for further details.</td>
     * </tr>
     * </tbody>
     * </table>
     */
    @Parameter( property = "flatten.mode", required = false )
    private FlattenMode flattenMode;

    /** The ArtifactFactory required to resolve POM using {@link #modelBuilder}. */
    // Neither ArtifactFactory nor DefaultArtifactFactory tells what to use instead
    @Component
    private ArtifactFactory artifactFactory;

    /** The {@link ModelInterpolator} used to resolve variables. */
    @Component( role = ModelInterpolator.class )
    private ModelInterpolator modelInterpolator;
    
    /** The {@link ModelInterpolator} used to resolve variables. */
    @Component( role = CiInterpolator.class)
    private CiInterpolator modelCiFriendlyInterpolator;

    /** The {@link MavenSession} used to get user properties. */
    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    @Component
    private DependencyResolver dependencyResolver;

    @Component
    private ProfileSelector profileSelector;

    @Component(role = ModelBuilder.class)
    private DefaultModelBuilder defaultModelBuilder;
    
    /**
     * The constructor.
     */
    public FlattenMojo()
    {
        super();
    }

    /**
     * {@inheritDoc}
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {

        getLog().info( "Generating flattened POM of project " + this.project.getId() + "..." );

        File originalPomFile = this.project.getFile();
        Model flattenedPom = createFlattenedPom( originalPomFile );
        String headerComment = extractHeaderComment( originalPomFile );

        File flattenedPomFile = getFlattenedPomFile();
        writePom( flattenedPom, flattenedPomFile, headerComment );

        if ( isUpdatePomFile() )
        {
            this.project.setPomFile( flattenedPomFile );
        }
    }

    /**
     * This method extracts the XML header comment if available.
     *
     * @param xmlFile is the XML {@link File} to parse.
     * @return the XML comment between the XML header declaration and the root tag or <code>null</code> if NOT
     *         available.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected String extractHeaderComment( File xmlFile )
        throws MojoExecutionException
    {

        try
        {
            SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            SaxHeaderCommentHandler handler = new SaxHeaderCommentHandler();
            parser.setProperty( "http://xml.org/sax/properties/lexical-handler", handler );
            parser.parse( xmlFile, handler );
            return handler.getHeaderComment();
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to parse XML from " + xmlFile, e );
        }
    }

    /**
     * Writes the given POM {@link Model} to the given {@link File}.
     *
     * @param pom the {@link Model} of the POM to write.
     * @param pomFile the {@link File} where to write the given POM will be written to. {@link File#getParentFile()
     *            Parent directories} are {@link File#mkdirs() created} automatically.
     * @param headerComment is the content of a potential XML comment at the top of the XML (after XML declaration and
     *            before root tag). May be <code>null</code> if not present and to be omitted in target POM.
     * @throws MojoExecutionException if the operation failed (e.g. due to an {@link IOException}).
     */
    protected void writePom( Model pom, File pomFile, String headerComment )
        throws MojoExecutionException
    {

        File parentFile = pomFile.getParentFile();
        if ( !parentFile.exists() )
        {
            boolean success = parentFile.mkdirs();
            if ( !success )
            {
                throw new MojoExecutionException( "Failed to create directory " + pomFile.getParent() );
            }
        }
        // MavenXpp3Writer could internally add the comment but does not expose such feature to API!
        // Instead we have to write POM XML to String and do post processing on that :(
        MavenXpp3Writer pomWriter = new MavenXpp3Writer();
        StringWriter stringWriter = new StringWriter( INITIAL_POM_WRITER_SIZE );
        try
        {
            pomWriter.write( stringWriter, pom );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Internal I/O error!", e );
        }
        StringBuffer buffer = stringWriter.getBuffer();
        if ( !StringUtils.isEmpty( headerComment ) )
        {
            int projectStartIndex = buffer.indexOf( "<project" );
            if ( projectStartIndex >= 0 )
            {
                buffer.insert( projectStartIndex, "<!--" + headerComment + "-->\n" );
            }
            else
            {
                getLog().warn( "POM XML post-processing failed: no project tag found!" );
            }
        }
        writeStringToFile( buffer.toString(), pomFile, pom.getModelEncoding() );
    }

    /**
     * Writes the given <code>data</code> to the given <code>file</code> using the specified <code>encoding</code>.
     *
     * @param data is the {@link String} to write.
     * @param file is the {@link File} to write to.
     * @param encoding is the encoding to use for writing the file.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected void writeStringToFile( String data, File file, String encoding )
        throws MojoExecutionException
    {

        OutputStream outStream = null;
        Writer writer = null;
        try
        {
            outStream = new FileOutputStream( file );
            writer = new OutputStreamWriter( outStream, encoding );
            writer.write( data );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to write to " + file, e );
        }
        finally
        {
            // resource-handling not perfectly solved but we do not want to require java 1.7
            // and this is not a server application.
            IOUtil.close( writer );
            IOUtil.close( outStream );
        }
    }

    /**
     * This method creates the flattened POM what is the main task of this plugin.
     *
     * @param pomFile is the name of the original POM file to read and transform.
     * @return the {@link Model} of the flattened POM.
     * @throws MojoExecutionException if anything goes wrong (e.g. POM can not be processed).
     * @throws MojoFailureException if anything goes wrong (logical error).
     */
    protected Model createFlattenedPom( File pomFile )
        throws MojoExecutionException, MojoFailureException
    {

        ModelBuildingRequest buildingRequest = createModelBuildingRequest( pomFile );
        Model effectivePom = createEffectivePom( buildingRequest, isEmbedBuildProfileDependencies(), this.flattenMode );

        Model flattenedPom = new Model();

        // keep original encoding (we could also normalize to UTF-8 here)
        String modelEncoding = effectivePom.getModelEncoding();
        if ( StringUtils.isEmpty( modelEncoding ) )
        {
            modelEncoding = "UTF-8";
        }
        flattenedPom.setModelEncoding( modelEncoding );

        Model cleanPom = createCleanPom( effectivePom, buildingRequest);

        FlattenDescriptor descriptor = getFlattenDescriptor();
        Model originalPom = this.project.getOriginalModel();
        Model resolvedPom = this.project.getModel();
        Model interpolatedPom = createResolvedPom( buildingRequest );

        // copy the configured additional POM elements...

        for ( PomProperty<?> property : PomProperty.getPomProperties() )
        {
            if ( property.isElement() )
            {
                Model sourceModel = getSourceModel( descriptor, property, effectivePom, originalPom, resolvedPom,
                                                    interpolatedPom, cleanPom );
                if ( sourceModel == null )
                {
                    if ( property.isRequired() )
                    {
                        throw new MojoFailureException( "Property " + property.getName()
                            + " is required and can not be removed!" );
                    }
                }
                else
                {
                    property.copy( sourceModel, flattenedPom );
                }
            }
        }

        return flattenedPom;
    }

    private Model createResolvedPom( ModelBuildingRequest buildingRequest )
    {
        LoggingModelProblemCollector problems = new LoggingModelProblemCollector( getLog() );
        Model originalModel = this.project.getOriginalModel().clone();
        if (this.flattenMode == FlattenMode.resolveCiFriendliesOnly) {
            return this.modelCiFriendlyInterpolator.interpolateModel( originalModel, this.project.getModel().getProjectDirectory(),
                                                            buildingRequest, problems );
        }
        return this.modelInterpolator.interpolateModel( originalModel, this.project.getModel().getProjectDirectory(),
                                                        buildingRequest, problems );
    }

    /**
     * This method creates the clean POM as a {@link Model} where to copy elements from that shall be
     * {@link ElementHandling#flatten flattened}. Will be mainly empty but contains some the minimum elements that have
     * to be kept in flattened POM.
     *
     * @param effectivePom is the effective POM.
     * @param buildingRequest
     * @return the clean POM.
     */
    protected Model createCleanPom(Model effectivePom, ModelBuildingRequest buildingRequest)
    {
        Model cleanPom = new Model();

        cleanPom.setGroupId( effectivePom.getGroupId() );
        cleanPom.setArtifactId( effectivePom.getArtifactId() );
        cleanPom.setVersion( effectivePom.getVersion() );
        cleanPom.setPackaging( effectivePom.getPackaging() );
        cleanPom.setLicenses( effectivePom.getLicenses() );
        // fixed to 4.0.0 forever :)
        cleanPom.setModelVersion( "4.0.0" );

        // plugins with extensions must stay
        Build build = effectivePom.getBuild();
        if ( build != null )
        {
            for ( Plugin plugin : build.getPlugins() )
            {
                if ( plugin.isExtensions() )
                {
                    Build cleanBuild = cleanPom.getBuild();
                    if ( cleanBuild == null )
                    {
                        cleanBuild = new Build();
                        cleanPom.setBuild( cleanBuild );
                    }
                    Plugin cleanPlugin = new Plugin();
                    cleanPlugin.setGroupId( plugin.getGroupId() );
                    cleanPlugin.setArtifactId( plugin.getArtifactId() );
                    cleanPlugin.setVersion( plugin.getVersion() );
                    cleanPlugin.setExtensions( true );
                    cleanBuild.addPlugin( cleanPlugin );
                }
            }
        }

        // transform profiles...
        for ( Profile profile : effectivePom.getProfiles() )
        {
            if ( !isEmbedBuildProfileDependencies() || !isBuildTimeDriven( profile.getActivation() ) )
            {
                if ( !isEmpty( profile.getDependencies() ) || !isEmpty( profile.getRepositories() ) )
                {
                    Profile strippedProfile = new Profile();
                    strippedProfile.setId( profile.getId() );
                    strippedProfile.setActivation( profile.getActivation() );
                    strippedProfile.setDependencies( profile.getDependencies() );
                    strippedProfile.setRepositories( profile.getRepositories() );
                    cleanPom.addProfile( strippedProfile );
                }
            }
        }

        // transform dependencies...
        List<Dependency> dependencies = createFlattenedDependencies( effectivePom, buildingRequest );
        cleanPom.setDependencies( dependencies );
        return cleanPom;
    }

    private Model getSourceModel( FlattenDescriptor descriptor, PomProperty<?> property, Model effectivePom,
                                  Model originalPom, Model resolvedPom, Model interpolatedPom, Model cleanPom )
    {

        ElementHandling handling = descriptor.getHandling( property );
        getLog().debug( "Property " + property.getName() + " will be handled using " + handling
            + " in flattened POM." );
        switch ( handling )
        {
            case expand:
                return effectivePom;
            case keep:
                return originalPom;
            case resolve:
                return resolvedPom;
            case interpolate:
                return interpolatedPom;
            case flatten:
                return cleanPom;
            case remove:
                return null;
            default:
                throw new IllegalStateException( handling.toString() );
        }
    }

    /**
     * Creates a flattened {@link List} of {@link Repository} elements where those from super-POM are omitted.
     *
     * @param repositories is the {@link List} of {@link Repository} elements. May be <code>null</code>.
     * @return the flattened {@link List} of {@link Repository} elements or <code>null</code> if <code>null</code> was
     *         given.
     */
    protected static List<Repository> createFlattenedRepositories( List<Repository> repositories )
    {
        if ( repositories != null )
        {
            List<Repository> flattenedRepositories = new ArrayList<Repository>( repositories.size() );
            for ( Repository repo : repositories )
            {
                // filter inherited repository section from super POM (see MOJO-2042)...
                if ( !isCentralRepositoryFromSuperPom( repo ) )
                {
                    flattenedRepositories.add( repo );
                }
            }
            return flattenedRepositories;
        }
        return repositories;
    }

    private FlattenDescriptor getFlattenDescriptor()
        throws MojoFailureException
    {
        FlattenDescriptor descriptor = this.pomElements;
        if ( descriptor == null )
        {
            FlattenMode mode = this.flattenMode;
            if ( mode == null )
            {
                mode = FlattenMode.defaults;
            }
            else if ( this.flattenMode == FlattenMode.minimum )
            {
                getLog().warn( "FlattenMode " + FlattenMode.minimum + " is deprecated!" );
            }
            descriptor = mode.getDescriptor();
            if ( "maven-plugin".equals( this.project.getPackaging() ) )
            {
                descriptor.setPrerequisites( ElementHandling.expand );
            }
        }
        else
        {
            if ( descriptor.isEmpty() )
            {
                // legacy approach...
                // Can't use Model itself as empty elements are never null, so you can't recognize if it was set or not
                Xpp3Dom rawDescriptor = this.mojoExecution.getConfiguration().getChild( "pomElements" );
                descriptor = new FlattenDescriptor( rawDescriptor );
            }
            if ( this.flattenMode != null )
            {
                descriptor = descriptor.merge( this.flattenMode.getDescriptor() );
            }
        }
        return descriptor;
    }

    /**
     * This method determines if the given {@link Repository} section is identical to what is defined from the super
     * POM.
     *
     * @param repo is the {@link Repository} section to check.
     * @return <code>true</code> if maven central default configuration, <code>false</code> otherwise.
     */
    private static boolean isCentralRepositoryFromSuperPom( Repository repo )
    {
        if ( repo != null )
        {
            if ( "central".equals( repo.getId() ) )
            {
                RepositoryPolicy snapshots = repo.getSnapshots();
                if ( snapshots != null && !snapshots.isEnabled() )
                {
                    return true;
                }
            }
        }
        return false;
    }

    private ModelBuildingRequest createModelBuildingRequest( File pomFile )
    {

        FlattenModelResolver resolver = new FlattenModelResolver( this.localRepository, this.artifactFactory,
            this.dependencyResolver, this.session.getProjectBuildingRequest(), this.session.getAllProjects() );
        Properties userProperties = this.session.getUserProperties();
        List<String> activeProfiles = this.session.getRequest().getActiveProfiles();

        // @formatter:off
        ModelBuildingRequest buildingRequest =
            new DefaultModelBuildingRequest().setUserProperties( userProperties ).setSystemProperties( System.getProperties() ).setPomFile( pomFile ).setModelResolver( resolver ).setActiveProfileIds( activeProfiles );
        // @formatter:on
        return buildingRequest;
    }

    /**
     * Creates the effective POM for the given <code>pomFile</code> trying its best to match the core maven behaviour.
     *
     * @param buildingRequest {@link ModelBuildingRequest}
     * @param embedBuildProfileDependencies embed build profiles yes/no.
     * @return the parsed and calculated effective POM.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected Model createEffectivePom( ModelBuildingRequest buildingRequest,
                                               final boolean embedBuildProfileDependencies, final FlattenMode flattenMode )
        throws MojoExecutionException
    {
        ModelBuildingResult buildingResult;
        try
        {
            ProfileInjector profileInjector = new ProfileInjector()
            {

                public void injectProfile( Model model, Profile profile, ModelBuildingRequest request,
                                           ModelProblemCollector problems )
                {
                    List<String> activeProfileIds = request.getActiveProfileIds();
                    if ( activeProfileIds.contains( profile.getId() ) )
                    {
                        Properties merged = new Properties();
                        merged.putAll( model.getProperties() );
                        merged.putAll( profile.getProperties() );
                        model.setProperties( merged );
                    }
                }
            };
            ProfileSelector profileSelector = new ProfileSelector()
            {
                public List<Profile> getActiveProfiles( Collection<Profile> profiles, ProfileActivationContext context,
                                                        ModelProblemCollector problems )
                {
                    List<Profile> activeProfiles = new ArrayList<Profile>( profiles.size() );

                    for ( Profile profile : profiles )
                    {
                        Activation activation = profile.getActivation();
                        if ( !embedBuildProfileDependencies || isBuildTimeDriven( activation ) )
                        {
                            activeProfiles.add( profile );
                        }
                    }

                    return activeProfiles;
                }
            };
            
            defaultModelBuilder.setProfileInjector( profileInjector ).setProfileSelector( profileSelector );
            //if (flattenMode == FlattenMode.resolveCiFriendliesOnly) {
            //	defaultModelBuilder.setModelInterpolator(new CiModelInterpolator());
            //}
            buildingResult = defaultModelBuilder.build( buildingRequest );
        }
        catch ( ModelBuildingException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

        Model effectivePom = buildingResult.getEffectiveModel();

        // LoggingModelProblemCollector problems = new LoggingModelProblemCollector( getLog() );
        // Model interpolatedModel =
        // this.modelInterpolator.interpolateModel( this.project.getOriginalModel(),
        // effectivePom.getProjectDirectory(), buildingRequest, problems );

        // remove Repositories from super POM (central)
        effectivePom.setRepositories( createFlattenedRepositories( effectivePom.getRepositories() ) );
        return effectivePom;
    }

    /**
     * Null-safe check for {@link Collection#isEmpty()}.
     *
     * @param collection is the {@link Collection} to test. May be <code>null</code>.
     * @return <code>true</code> if <code>null</code> or {@link Collection#isEmpty() empty}, <code>false</code>
     *         otherwise.
     */
    private boolean isEmpty( Collection<?> collection )
    {
        if ( collection == null )
        {
            return true;
        }
        return collection.isEmpty();
    }

    /**
     * @return <code>true</code> if build-dependent profiles (triggered by OS or JDK) should be evaluated and their
     *         effect (variables and dependencies) are resolved and embedded into the flattened POM while the profile
     *         itself is stripped. Otherwise if <code>false</code> the profiles will remain untouched.
     */
    public boolean isEmbedBuildProfileDependencies()
    {

        return this.embedBuildProfileDependencies.booleanValue();
    }

    public boolean isEmbedAllProfileDependencies()
    {

        return this.embedAllProfileDependencies.booleanValue();
    }

    /**
     * @param activation is the {@link Activation} of a {@link Profile}.
     * @return <code>true</code> if the given {@link Activation} is build-time driven, <code>false</code> otherwise (if
     *         it is triggered by OS or JDK).
     */
    protected boolean isBuildTimeDriven( Activation activation )
    {
        if (isEmbedAllProfileDependencies()) return true;

        if ( activation == null )
        {
            return true;
        }
        if ( StringUtils.isEmpty( activation.getJdk() ) && activation.getOs() == null )
        {
            return true;
        }
        return false;
    }

    /**
     * Creates the {@link List} of {@link Dependency dependencies} for the flattened POM. These are all resolved
     * {@link Dependency dependencies} except for those added from {@link Profile profiles}.
     *
     * @param effectiveModel is the effective POM {@link Model} to process.
     * @return the {@link List} of {@link Dependency dependencies}.
     */
    protected List<Dependency> createFlattenedDependencies(final Model effectiveModel, final ModelBuildingRequest modelBuildingRequest )
    {

        List<Dependency> flattenedDependencies = new ArrayList<Dependency>();
        // resolve all direct and inherited dependencies...
        createFlattenedDependencies( effectiveModel, flattenedDependencies );
        if ( isEmbedBuildProfileDependencies() )
        {
            final Model projectModel = this.project.getModel();
            Dependencies modelDependencies = new Dependencies();
            modelDependencies.addAll( projectModel.getDependencies() );

            Set<String> activeProfile = resolveActiveProfileIds(effectiveModel, modelBuildingRequest);

            for ( Profile profile : projectModel.getProfiles() )
            {
                // build-time driven activation (by property or file)?
                if ( isBuildTimeDriven( profile.getActivation() ) && activeProfile.contains(profile.getId()))
                {
                    List<Dependency> profileDependencies = profile.getDependencies();
                    for ( Dependency profileDependency : profileDependencies )
                    {
                        if ( modelDependencies.contains( profileDependency ) )
                        {
                            // our assumption here is that the profileDependency has been added to model because of
                            // this build-time driven profile. Therefore we need to add it to the flattened POM.
                            // Non build-time driven profiles will remain in the flattened POM with their dependencies
                            // and
                            // allow dynamic dependencies due to OS or JDK.
                            int depIndex = findDependencyToOverride(flattenedDependencies, profileDependency);
                            if (depIndex == -1) {
                                flattenedDependencies.add(profileDependency);
                            } else {
                                flattenedDependencies.set(depIndex, profileDependency);
                            }
                        }
                    }
                }
            }
            getLog().debug( "Resolved " + flattenedDependencies.size() + " dependency/-ies for flattened POM." );
        }
        return flattenedDependencies;
    }

    private int findDependencyToOverride(List<Dependency> dependencies, Dependency override) {

        for( int i = 0; i < dependencies.size(); i++ )
        {
            Dependency candidate = dependencies.get(i);
            if (isOverrideOf(override, candidate))
            {
                return i;
            }
        }
        return -1;
    }

    private boolean isOverrideOf(Dependency override, Dependency candidate) {
        Dependency clone = candidate.clone();
        clone.setVersion(override.getVersion());
        return clone.getManagementKey().equals(override.getManagementKey());
    }

    private Set<String> resolveActiveProfileIds(final Model effectiveModel, final ModelBuildingRequest buildingRequest) {
        DefaultProfileActivationContext context = new DefaultProfileActivationContext();
        context.setActiveProfileIds(buildingRequest.getActiveProfileIds());
        context.setInactiveProfileIds(buildingRequest.getInactiveProfileIds());
        context.setSystemProperties(buildingRequest.getSystemProperties());
        context.setUserProperties(buildingRequest.getUserProperties());
        context.setProjectDirectory(buildingRequest.getPomFile() != null ? buildingRequest.getPomFile().getParentFile() : null);

        List<Profile> activeProfiles = profileSelector.getActiveProfiles(effectiveModel.getProfiles(), context, new ModelProblemCollector() {
            @Override
            public void add(ModelProblemCollectorRequest modelProblemCollectorRequest) {

            }
        });


        Set<String> activeProfile = new HashSet<String>();
        for(Profile p : activeProfiles) {
            activeProfile.add(p.getId());
        }
        return activeProfile;
    }

    /**
     * Collects the resolved {@link Dependency dependencies} from the given <code>effectiveModel</code>.
     *
     * @param effectiveModel is the effective POM {@link Model} to process.
     * @param flattenedDependencies is the {@link List} where to add the collected {@link Dependency dependencies}.
     */
    protected void createFlattenedDependencies( Model effectiveModel, List<Dependency> flattenedDependencies )
    {

        getLog().debug( "Resolving dependencies of " + effectiveModel.getId() );
        // this.project.getDependencies() already contains the inherited dependencies but also those from profiles
        // List<Dependency> projectDependencies = currentProject.getOriginalModel().getDependencies();
        List<Dependency> projectDependencies = effectiveModel.getDependencies();
        for ( Dependency projectDependency : projectDependencies )
        {
            Dependency flattenedDependency = createFlattenedDependency( projectDependency );
            if ( flattenedDependency != null )
            {
                flattenedDependencies.add( flattenedDependency );
            }
        }
    }

    /**
     * @param projectDependency is the project {@link Dependency}.
     * @return the flattened {@link Dependency} or <code>null</code> if the given {@link Dependency} is NOT relevant for
     *         flattened POM.
     */
    protected Dependency createFlattenedDependency( Dependency projectDependency )
    {

        return "test".equals( projectDependency.getScope() ) ? null : projectDependency;
    }

    /**
     * @return <code>true</code> if the generated flattened POM shall be {@link MavenProject#setFile(java.io.File) set}
     *         as POM artifact of the {@link MavenProject}, <code>false</code> otherwise.
     */
    public boolean isUpdatePomFile()
    {

        if ( this.updatePomFile == null )
        {
            if ( this.flattenMode == FlattenMode.bom )
            {
                return true;
            }
            return !this.project.getPackaging().equals( "pom" );
        }
        else
        {
            return this.updatePomFile.booleanValue();
        }
    }

    /**
     * This class is a simple SAX handler that extracts the first comment located before the root tag in an XML
     * document.
     */
    private class SaxHeaderCommentHandler
        extends DefaultHandler2
    {

        /** <code>true</code> if root tag has already been visited, <code>false</code> otherwise. */
        private boolean rootTagSeen;

        /** @see #getHeaderComment() */
        private String headerComment;

        /**
         * The constructor.
         */
        public SaxHeaderCommentHandler()
        {

            super();
            this.rootTagSeen = false;
        }

        /**
         * @return the XML comment from the header of the document or <code>null</code> if not present.
         */
        public String getHeaderComment()
        {

            return this.headerComment;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void comment( char[] ch, int start, int length )
            throws SAXException
        {

            if ( !this.rootTagSeen )
            {
                if ( this.headerComment == null )
                {
                    this.headerComment = new String( ch, start, length );
                }
                else
                {
                    getLog().warn( "Ignoring multiple XML header comment!" );
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startElement( String uri, String localName, String qName, Attributes atts )
            throws SAXException
        {

            this.rootTagSeen = true;
        }
    }

}
