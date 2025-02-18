package aQute.bnd.repository.maven.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.w3c.dom.Document;

import aQute.bnd.build.DownloadBlocker;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.http.HttpClient;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Processor;
import aQute.bnd.osgi.resource.ResourceUtils;
import aQute.bnd.osgi.resource.ResourceUtils.IdentityCapability;
import aQute.bnd.service.RepositoryPlugin.PutOptions;
import aQute.bnd.service.RepositoryPlugin.PutResult;
import aQute.bnd.service.maven.PomOptions;
import aQute.bnd.service.progress.ProgressPlugin;
import aQute.bnd.service.progress.ProgressPlugin.Task;
import aQute.bnd.test.jupiter.InjectTemporaryDirectory;
import aQute.bnd.version.Version;
import aQute.http.testservers.HttpTestServer.Config;
import aQute.lib.io.IO;
import aQute.lib.xml.XML;
import aQute.maven.api.Archive;
import aQute.maven.api.IPom;
import aQute.maven.api.MavenScope;
import aQute.maven.api.Revision;
import aQute.maven.provider.FakeNexus;
import aQute.maven.provider.MavenRepository;
import aQute.maven.provider.POM;

/*
 * Create a remote and local repository with a lot of bad stuff. The repo contains:
 *
 */
public class MavenBndRepoTest {
	private static final Version		DTO_VERSION	= Version.parseVersion("1.0.0.201505202023");
	final static DocumentBuilderFactory	dbf			= XML.newDocumentBuilderFactory();
	final static XPathFactory			xpf			= XPathFactory.newInstance();
	@InjectTemporaryDirectory
	File								tmp;
	File								local;
	File								remote;
	File								staging;
	File								index;

	private MavenBndRepository			repo;
	private FakeNexus					fnx;
	private Processor					domain;

	@BeforeEach
	public void setUp() throws Exception {
		local = IO.getFile(tmp, "local");
		remote = IO.getFile(tmp, "remote");
		staging = IO.getFile(tmp, "staging");
		index = IO.getFile(tmp, "index");
		remote.mkdirs();
		local.mkdirs();
		staging.mkdirs();

		IO.copy(IO.getFile("testresources/mavenrepo"), remote);
		IO.copy(IO.getFile("testresources/mavenrepo/index.maven"), index);

		Config config = new Config();
		fnx = new FakeNexus(config, remote);
		fnx.start();
	}

	@AfterEach
	public void tearDown() throws Exception {
		IO.close(repo);
		IO.close(fnx);
		IO.close(domain);
	}

	@Test
	public void testAutomaticSources() throws Exception {
		config(null);
		assertThat(repo.getStatus()).isNull();
		File sources = repo.get("org.osgi.dto.source", DTO_VERSION, null);
		assertThat(sources).isFile();
	}

	@Test
	public void testAutomaticSourcesForSnapshotWithoutBsn() throws Exception {
		config(null);
		assertThat(repo.getStatus()).isNull();
		String title = repo.title("org.apache.commons.cli");

		File commons = repo.get("commons-cli:commons-cli", new Version("1.2.0"), null);
		assertThat(commons).isFile();

		File source = repo.get("commons-cli:commons-cli.source", new Version("1.2.0"), null);
		assertThat(source).isFile();
	}

	@Test
	public void testProgramRemoveFromIndex() throws Exception {
		config(null);
		assertThat(repo.getStatus()).isNull();
		assertNotNull(repo.get("org.osgi.dto", DTO_VERSION, null));
		String indexContent = IO.collect(index);
		assertThat(indexContent).contains("org.osgi.dto:1.0.0");

		Map<String, Runnable> actions = repo.actions("org.osgi.dto");
		assertNotNull(actions);
		actions.get("Delete All from Index")
			.run();
		assertNull(repo.get("org.osgi.dto", DTO_VERSION, null));
		assertThat(repo.list("org.osgi.dto")).isEmpty();
		indexContent = IO.collect(index);
		assertThat(indexContent).doesNotContain("org.osgi.dto:1.0.0");
	}

	@Test
	public void testRevisionRemoveFromIndex() throws Exception {
		config(null);
		assertNotNull(repo.get("org.osgi.dto", DTO_VERSION, null));
		String indexContent = IO.collect(index);

		Map<String, Runnable> actions = repo.actions("org.osgi.dto", DTO_VERSION);
		assertNotNull(actions);
		actions.get("Delete from Index")
			.run();
		assertThat(repo.list("org.osgi.dto")).isEmpty();
		indexContent = IO.collect(index);
		assertThat(indexContent).doesNotContain("org.osgi.dto:1.0.0");
	}

	@Test
	public void testAdd() throws Exception {
		config(null);
		assertThat(repo.list("org.osgi.service.log")).isEmpty();
		String indexContent = IO.collect(index);
		assertThat(indexContent).doesNotContain("org.osgi.service.log");
		repo.index.add(Archive.valueOf("org.osgi:org.osgi.service.log:1.3.0"));
		assertThat(repo.list("org.osgi.service.log")).isNotEmpty();
		indexContent = IO.collect(index);
		assertThat(indexContent).contains("org.osgi.service.log:1.3.0");
	}

	@Test
	public void testBadUrl() throws Exception {
		Map<String, String> config = new HashMap<>();
		config.put("releaseUrl", "httpx://goo.bar/adshasdh");
		config(config);
		repo.list(null);
		assertThat(repo.getStatus()).contains("Invalid scheme");
	}

	@Test
	public void testUseSource() throws Exception {
		Map<String, String> config = new HashMap<>();
		config.put("source", "org.osgi:org.osgi.service.log:1.3.0, ; \torg.osgi:org.osgi.service.log:1.2.0");
		config(config);
		assertThat(repo.getStatus()).isNull();
		assertThat(repo.list("org.osgi.service.log")).isNotEmpty();
	}

	@Test
	public void testUseSourceWithError() throws Exception {
		Map<String, String> config = new HashMap<>();
		config.put("source", "foobar\n");
		config(config);
		repo.list(null);
		assertThat(repo.getStatus()).contains("foobar");
	}

	@Test
	public void testTooltip() throws Exception {
		config(null);
		assertThat(repo.init()).isTrue();

		String tooltip = repo.tooltip();
		assertNotNull(tooltip);

		String title = repo.title("org.apache.commons.cli");
		assertEquals("org.apache.commons.cli", title);

		tooltip = repo.tooltip("org.apache.commons.cli", new Version("1.2"));
		assertNotNull(tooltip);
		System.out.println(tooltip);
		title = repo.title("org.apache.commons.cli", new Version("1.2"));
		assertEquals("1.2.0", title);
		tooltip = repo.tooltip("commons-cli:commons-cli", new Version("1.0"));
		assertNotNull(tooltip);
		System.out.println(tooltip);

		title = repo.title("commons-cli:commons-cli");
		assertEquals("commons-cli:commons-cli [!]", title);

		title = repo.title("commons-cli:commons-cli", new Version("1.0"));
		assertEquals("1.0.0 [" + Constants.NOT_A_BUNDLE_S + "]", title);

		title = repo.title("commons-cli:commons-cli", new Version("1.4.0.SNAPSHOT"));
		assertThat(title).contains("1.4.0.SNAPSHOT [empty file");

	}

	@Test
	public void testZipFileWithContents() throws Exception {
		IO.copy(IO.getFile("testresources/mavenrepo3"), remote);
		IO.copy(IO.getFile("testresources/mavenrepo3/index.maven"), index);
		Map<String, String> config = new HashMap<>();
		config.put("index", index.getAbsolutePath());
		config.put("multi", " zip , par, foo");
		config(config);
		String multi_version = "1.0.0";
		domain.setProperty("multi_version", multi_version);
		File file = repo.get("group:artifact:zip:", Version.parseVersion(multi_version), null);
		assertThat(repo.getStatus()).isNull();
		assertNotNull(file);
		assertTrue(file.isFile());

		File file2 = repo.get("name.njbartlett.eclipse.macbadge", new Version("1.0.0.201110100042"), null);
		assertNotNull(file2);

		File file3 = repo.get("group:artifact:jar:name_njbartlett_eclipse_macbadge_1_0_0_201110100042",
			Version.parseVersion(multi_version), null);
		assertNotNull(file3);
		assertTrue(file3.isFile());

		assertTrue(file2.equals(file3));

		repo.index.remove(Archive.valueOf("group:artifact:zip:" + multi_version));

		file2 = repo.get("name.njbartlett.eclipse.macbadge", new Version("1.0.0.201110100042"), null);
		assertNull(file2);
		file3 = repo.get("group:artifact:jar:1003", Version.parseVersion(multi_version), null);
		assertNull(file3);
	}

	@Test
	public void testPut() throws Exception {

		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		File jar = IO.getFile("testresources/release.jar");
		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 140212);
		File file = assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.pom", 1470);
		IPom pom = repo.storage.getPom(new FileInputStream(file));
		assertEquals("jar", pom.getPackaging());
		assertEquals(true, pom.hasValidGAV());
		assertEquals("biz.aQute.bnd:biz.aQute.bnd.maven:3.2.0", pom.getRevision()
			.toString());

		Document doc = dbf.newDocumentBuilder()
			.parse(file);
		XPath xp = xpf.newXPath();

		assertEquals("biz.aQute.bnd.maven", xp.evaluate("/project/artifactId", doc)
			.trim());
		assertEquals("3.2.0", xp.evaluate("/project/version", doc)
			.trim());
		assertEquals("biz.aQute.bnd", xp.evaluate("/project/groupId", doc)
			.trim());

		assertEquals("http://bnd.bndtools.org/", xp.evaluate("/project/url", doc)
			.trim());
		assertEquals("Bndtools", xp.evaluate("/project/organization/name", doc)
			.trim());
		assertEquals("http://bndtools.org/", xp.evaluate("/project/organization/url", doc)
			.trim());

		assertEquals("https://github.com/bndtools/bnd", xp.evaluate("/project/scm/url", doc)
			.trim());
		assertEquals("scm:git:https://github.com/bndtools/bnd.git", xp.evaluate("/project/scm/connection", doc)
			.trim());
		assertEquals("scm:git:git@github.com:bndtools/bnd.git", xp.evaluate("/project/scm/developerConnection", doc)
			.trim());

		// IO.copy(file, System.out);

		put = repo.put(new FileInputStream(jar), null);

		Requirement wc = ResourceUtils.createWildcardRequirement();
		Collection<Capability> caps = repo.findProviders(Collections.singleton(wc))
			.get(wc);
		Set<Resource> resources = ResourceUtils.getResources(caps);
		assertEquals(3, resources.size());
		IdentityCapability bc = ResourceUtils.getIdentityCapability(resources.iterator()
			.next());
		assertEquals("biz.aQute.bnd.maven", bc.osgi_identity());
	}

	@Test
	public void testPutMavenReleaseSources() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		try (Processor context = new Processor()) {
			context.setProperty("-maven-release", "sources;path=\"testresources/src\",javadoc;packages=all");
			File jar = IO.getFile("testresources/release-nosource.jar");
			PutOptions options = new PutOptions();
			options.context = context;
			PutResult put = repo.put(new FileInputStream(jar), options);

			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 89400);
			File sources = assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-sources.jar", 0);
			try (Jar sourcesJar = new Jar(sources)) {
				assertThat(sourcesJar.getResources()).containsKeys("X.java");
			}
			File javadoc = assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-javadoc.jar", 0);
			try (Jar javadocJar = new Jar(javadoc)) {
				assertThat(javadocJar.getResources()).containsKeys("X.html");
			}
		}
	}

	@Test
	public void testPutMavenReleaseSourcesDefaultSourcepath() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		try (Processor context = new Processor()) {
			context.setProperty("-sourcepath", "testresources/srcpath2");
			context.setProperty("-maven-release", "javadoc;packages=all");
			File jar = IO.getFile("testresources/release-nosource.jar");
			PutOptions options = new PutOptions();
			options.context = context;
			PutResult put = repo.put(new FileInputStream(jar), options);

			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 89400);
			File sources = assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-sources.jar", 0);
			try (Jar sourcesJar = new Jar(sources)) {
				assertThat(sourcesJar.getResources())
					.containsKeys("aQute/maven/bnd/LocalRepoWatcher.java", "aQute/maven/bnd/MavenBndRepository.java",
						"aQute/maven/bnd/MavenPlugin.java", "aQute/maven/bnd/ReleaseDTO.java",
						"aQute/maven/bnd/package-info.java")
					.doesNotContainKeys("aQute/bnd/tool/Tool.java", "aQute/bnd/tool/packageinfo");
			}
			File javadoc = assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-javadoc.jar", 0);
			try (Jar javadocJar = new Jar(javadoc)) {
				assertThat(javadocJar.getResources())
					.containsKeys("aQute/maven/bnd/LocalRepoWatcher.html", "aQute/maven/bnd/MavenBndRepository.html",
						"aQute/maven/bnd/MavenPlugin.html", "aQute/maven/bnd/ReleaseDTO.html")
					.doesNotContainKeys("aQute/bnd/tool/Tool.html");
			}
		}
	}

	@Test
	public void testPutMavenExtraFiles() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		try (Processor context = new Processor()) {
			context.setProperty("test", "1234");
			context.setProperty("-maven-release",
				"archive;path=testresources/extra/test.foo,"
					+ "archive;path={testresources/extra/test.bar};classifier=BAR");

			File jar = IO.getFile("testresources/release-nosource.jar");
			PutOptions options = new PutOptions();
			options.context = context;
			PutResult put = repo.put(new FileInputStream(jar), options);
			assertThat(context.check()).isTrue();

			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 89400);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-classifier-0.foo",
				16);
			String content = IO
				.collect(new File(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-BAR.bar"));
			assertThat(content).contains("this is test bar with 1234 x");
		}
	}

	@Test
	public void testPutMavenExtraNoFile() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		try (Processor context = new Processor()) {
			context.setProperty("-maven-release", "archive;path=i_do_not_exist");

			File jar = IO.getFile("testresources/release-nosource.jar");
			PutOptions options = new PutOptions();
			options.context = context;
			PutResult put = repo.put(new FileInputStream(jar), options);
			context.getInfo(repo.reporter);
			assertThat(context.check("No metadata for revision", "i_do_not_exist")).isTrue();

			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 89400);
		}
	}

	@Test
	public void testPutMavenExtraNoPath() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		try (Processor context = new Processor()) {
			context.setProperty("-maven-release", "archive");

			File jar = IO.getFile("testresources/release-nosource.jar");
			PutOptions options = new PutOptions();
			options.context = context;
			PutResult put = repo.put(new FileInputStream(jar), options);
			context.getInfo(repo.reporter);
			assertThat(context.check("No metadata for revision", "has an 'archive' without the path attribute"))
				.isTrue();

			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 89400);
		}
	}

	@Test
	public void testPutMavenReleaseSourcesSourcepath() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		try (Processor context = new Processor()) {
			context.setProperty("project.allsourcepath",
				"${path;${osfile;testresources;srcpath1};${osfile;testresources;srcpath2}}");
			context.setProperty("-maven-release",
				"sources;-sourcepath=\"${project.allsourcepath}\",javadoc;packages=all");
			File jar = IO.getFile("testresources/release-nosource.jar");
			PutOptions options = new PutOptions();
			options.context = context;
			PutResult put = repo.put(new FileInputStream(jar), options);

			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 89400);
			File sources = assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-sources.jar", 0);
			try (Jar sourcesJar = new Jar(sources)) {
				assertThat(sourcesJar.getResources()).containsKeys("aQute/bnd/tool/Tool.java",
					"aQute/bnd/tool/packageinfo", "aQute/maven/bnd/LocalRepoWatcher.java",
					"aQute/maven/bnd/MavenBndRepository.java", "aQute/maven/bnd/MavenPlugin.java",
					"aQute/maven/bnd/ReleaseDTO.java", "aQute/maven/bnd/package-info.java");
			}
			File javadoc = assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0-javadoc.jar", 0);
			try (Jar javadocJar = new Jar(javadoc)) {
				assertThat(javadocJar.getResources()).containsKeys("aQute/bnd/tool/Tool.html",
					"aQute/maven/bnd/LocalRepoWatcher.html", "aQute/maven/bnd/MavenBndRepository.html",
					"aQute/maven/bnd/MavenPlugin.html", "aQute/maven/bnd/ReleaseDTO.html");
			}
		}
	}

	@Test
	public void testPomGenerate() throws Exception {
		config(null);
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		PomOptions po = new PomOptions();
		po.gav = "test:test:1.0";
		po.parent = null;
		po.dependencyManagement = false;

		repo.toPom(bout, po);
		ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
		POM pom = new POM((MavenRepository) repo.storage, bin);

		assertEquals(Revision.valueOf("test:test:1.0"), pom.getRevision());
		assertEquals(4, pom.getDependencies(EnumSet.of(MavenScope.runtime), false)
			.size());
		System.out.println(new String(bout.toByteArray(), StandardCharsets.UTF_8));
	}

	@Test
	public void testPutLocalTwiceNoSnapshot() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		Requirement wc = ResourceUtils.createWildcardRequirement();
		Collection<Capability> caps = repo.findProviders(Collections.singleton(wc))
			.get(wc);
		Set<Resource> resources = ResourceUtils.getResources(caps);
		int size = resources.size();
		assertThat(resources).extracting(ResourceUtils::getIdentityCapability)
			.filteredOn(Objects::nonNull)
			.extracting(IdentityCapability::osgi_identity)
			.doesNotContain("biz.aQute.bnd.maven");

		File jar = IO.getFile("testresources/release.jar");
		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 140212);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.pom", 1470);
		put = repo.put(new FileInputStream(jar), null);

		caps = repo.findProviders(Collections.singleton(wc))
			.get(wc);
		resources = ResourceUtils.getResources(caps);
		assertThat(resources).extracting(ResourceUtils::getIdentityCapability)
			.filteredOn(Objects::nonNull)
			.extracting(IdentityCapability::osgi_identity)
			.contains("biz.aQute.bnd.maven")
			.hasSize(size + 1);
	}

	@Test
	public void testPutReleaseAndThenIndex() throws Exception {
		Workspace ws = new Workspace(IO.getFile("testdata/releasews"));
		Project p1 = ws.getProject("p1");
		Project indexProject = ws.getProject("index");

		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(ws, map);

		repo.begin(indexProject);
		File jar = IO.getFile("testresources/release.jar");
		PutOptions po = new PutOptions();
		po.context = p1;
		PutResult put = repo.put(new FileInputStream(jar), po);

		File demoJar = IO.getFile("testresources/demo.jar");
		PutOptions indexPo = new PutOptions();
		indexPo.context = indexProject;
		put = repo.put(new FileInputStream(demoJar), indexPo);

		repo.end(indexProject);

		assertTrue(indexProject.check());
		assertTrue(IO.getFile(remote, "biz/aQute/bnd/demo/1.0.0/demo-1.0.0-index.xml")
			.isFile());
	}

	@Test
	public void testNoIndexFile() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("index", "generated/does_not_exist");
		config(map);
		repo.list(null);
		assertTrue(domain.check());
	}

	@Test
	public void testGet() throws Exception {
		config(null);
		File file = repo.get("commons-cli:commons-cli", new Version("1.0.0"), null);
		assertNotNull(file);
		assertTrue(file.isFile());
	}

	@Test
	public void testGetAttributes() throws Exception {
		config(null);
		DownloadBlocker db = new DownloadBlocker(null);
		File file = repo.get("commons-cli:commons-cli", new Version("1.0.0"), null, db);
		assertThat(file).isFile();
		assertThat(db.getFile()).isEqualTo(file);
		assertThat(db.getAttributes()).containsEntry("maven-groupId", "commons-cli")
			.containsEntry("maven-artifactId", "commons-cli")
			.containsEntry("maven-version", "1.0")
			.containsEntry("maven-classifier", "")
			.containsEntry("maven-extension", "jar");
	}

	@Test
	public void testGetWithSource() throws Exception {
		config(null);
		File jar = repo.get("org.osgi.dto", new Version("1.0.0.201505202023"), null);
		assertThat(jar).isFile();

		File sources = repo.get("org.osgi.dto.source", new Version("1.0.0.201505202023"), null);
		assertThat(sources).isFile();

	}

	@Test
	public void testGetFileRepo() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);
		File file = repo.get("commons-cli:commons-cli", new Version("1.0.0"), null);
		assertNotNull(file);
		assertTrue(file.isFile());
	}

	/*
	 * Commons CLI 1.2 is in there as GAV & as BSN
	 */
	@Test
	public void testGetViaBSNAndGAV() throws Exception {
		config(null);

		assertEquals(1, repo.list("org.apache.commons.cli")
			.size());
		System.out.println(repo.list("org.apache.commons.cli"));
		System.out.println(repo.versions("org.apache.commons.cli"));
		File f12maven = repo.get("commons-cli:commons-cli", new Version("1.2.0"), null);
		File f12osgi = repo.get("org.apache.commons.cli", new Version("1.2.0"), null);

		assertEquals("commons-cli-1.2.jar", f12maven.getName());
		assertEquals(f12maven, f12osgi);
	}

	@Test
	public void testList() throws Exception {
		config(null);
		List<String> l = repo.list(null);
		System.out.println(l);
		assertEquals(5, l.size());
		assertTrue(l.contains("commons-cli:commons-cli"));

		SortedSet<Version> versions = repo.versions("commons-cli:commons-cli");
		assertEquals("[1.0.0, 1.2.0, 1.4.0.SNAPSHOT]", versions.toString());

		versions = repo.versions("org.apache.commons.cli");
		assertEquals("[1.2.0]", versions.toString());

		versions = repo.versions("org.osgi.dto");
		assertEquals("[1.0.0.201505202023]", versions.toString());

		Requirement all = ResourceUtils.createWildcardRequirement();
		Collection<Capability> providers = repo.findProviders(Collections.singleton(all))
			.get(all);
		Set<Resource> resources = ResourceUtils.getResources(providers);

		// there are only two bundles in the store
		assertEquals(2, resources.size());
	}

	@Test
	public void testPutDefaultLocal() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", null);
		config(map);

		File jar = IO.getFile("testresources/release.jar");

		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.jar", 0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/biz.aQute.bnd.maven-3.2.0.pom", 0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0/maven-metadata-local.xml", 0);

		String s = IO.collect(index);
		assertTrue(s.contains("biz.aQute.bnd.maven"));
	}

	@Test
	public void testPutDefaultLocalSnapshot() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", null);
		config(map);

		File jar = IO.getFile("testresources/snapshot.jar");

		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar",
			0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom",
			0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata-local.xml", 0);

		String s = IO.collect(index);
		// snapshots added to index
		assertThat(s).contains("biz.aQute.bnd.maven");
	}

	@Test
	public void testPutLocalSnapshotSourceRelease() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", null);
		config(map);

		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "sources;force=true");
			PutOptions put = new PutOptions();
			put.context = context;

			File jar = IO.getFile("testresources/snapshot.jar");
			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar", 0);
			assertFileNotExists(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar");
			assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata-local.xml", 0);

			String s = IO.collect(index);
			// snapshots added to index
			assertThat(s).contains("biz.aQute.bnd.maven");
		}

	}

	@Test
	public void testPutLocalSnapshotJavadocRelease() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", null);
		config(map);

		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "javadoc;force=true");
			PutOptions put = new PutOptions();
			put.context = context;

			File jar = IO.getFile("testresources/snapshot.jar");
			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom", 0);
			assertFileNotExists(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar");
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar", 0);
			assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata-local.xml", 0);

			String s = IO.collect(index);
			// snapshots added to index
			assertThat(s).contains("biz.aQute.bnd.maven");
		}
	}

	@Test
	public void testPutLocalSnapshotJavadocAndSourceRelease() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", null);
		config(map);

		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "javadoc;force=true,sources;force=true");
			PutOptions put = new PutOptions();
			put.context = context;

			File jar = IO.getFile("testresources/snapshot.jar");
			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar", 0);
			assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata-local.xml", 0);

			String s = IO.collect(index);
			// snapshots added to index
			assertThat(s).contains("biz.aQute.bnd.maven");
		}
	}

	@Test
	public void testPutLocalSnapshotForceFullReleaseDefault() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", null);
		config(map);

		File jar = IO.getFile("testresources/snapshot.jar");

		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar",
			0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom",
			0);
		assertFileNotExists(local,
			"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar");
		assertFileNotExists(local,
			"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar");
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata-local.xml", 0);

		String s = IO.collect(index);
		// snapshots added to index
		assertThat(s).contains("biz.aQute.bnd.maven");
	}

	@Test
	public void testPutDefaultLocalSnapshotNoUpdate() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", null);
		map.put("noupdateOnRelease", "true");
		config(map);

		File jar = IO.getFile("testresources/snapshot.jar");

		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar",
			0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom",
			0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata-local.xml", 0);

		String s = IO.collect(index);
		// snapshots not added to index
		assertThat(s).doesNotContain("biz.aQute.bnd.maven");
	}

	@Test
	public void testPutDefaultLocalSnapshotFileRepo() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("snapshotUrl", remote.toURI()
			.toString());
		config(map);

		File jar = IO.getFile("testresources/snapshot.jar");

		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar",
			0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom",
			0);

		String s = IO.collect(index);
		// snapshots added to index
		assertThat(s).contains("biz.aQute.bnd.maven");
	}

	@Test
	public void testPutDefaultLocalSnapshotFileRepoNoUpdate() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("snapshotUrl", remote.toURI()
			.toString());
		map.put("noupdateOnRelease", "true");
		config(map);

		File jar = IO.getFile("testresources/snapshot.jar");

		PutResult put = repo.put(new FileInputStream(jar), null);

		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar",
			0);
		assertIsFile(local, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom",
			0);

		String s = IO.collect(index);
		// snapshots not added to index
		assertThat(s).doesNotContain("biz.aQute.bnd.maven");
	}

	@Test
	public void testPutRemoteSnapshot() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", null);
		map.put("snapshotUrl", fnx.getBaseURI() + "/repo/");
		config(map);

		File jar = IO.getFile("testresources/snapshot.jar");
		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "remote;snapshot=1");
			PutOptions put = new PutOptions();
			put.context = context;

			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.pom", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-sources.jar",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-javadoc.jar",
				0);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml", 0);

			File f = IO.getFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml");
			String s = IO.collect(f);
			assertTrue(s.contains("3.2.0-19700101.000000"));
		}

		//
		// Now try to update it
		//

		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "remote;snapshot=10000");
			PutOptions put = new PutOptions();
			put.context = context;

			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.pom", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-sources.jar",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-javadoc.jar",
				0);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml", 0);

			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2.pom", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2-sources.jar",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2-javadoc.jar",
				0);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml", 0);

			File f = IO.getFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml");
			String s = IO.collect(f);
			assertTrue(s.contains("<value>3.2.0-19700101.000000-1</value>"));
			assertTrue(s.contains("<value>3.2.0-19700101.000010-2</value>"));
		}

	}

	@Test
	public void testPutRemoteSnapshotFileRepo() throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put("snapshotUrl", remote.toURI()
			.toString());
		config(map);

		File jar = IO.getFile("testresources/snapshot.jar");
		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "remote;snapshot=1");
			PutOptions put = new PutOptions();
			put.context = context;

			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.pom", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-sources.jar",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-javadoc.jar",
				0);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml", 0);

			File f = IO.getFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml");
			String s = IO.collect(f);
			assertTrue(s.contains("3.2.0-19700101.000000"));
		}

		//
		// Now try to update it
		//

		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "remote;snapshot=10000");
			PutOptions put = new PutOptions();
			put.context = context;

			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT.pom", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-sources.jar", 0);
			assertIsFile(local,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-SNAPSHOT-javadoc.jar", 0);

			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.pom", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-sources.jar",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000000-1-javadoc.jar",
				0);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml", 0);

			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2.pom", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2.pom.sha1",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2.pom.md5",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2-sources.jar",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000010-2-javadoc.jar",
				0);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml", 0);

			File f = IO.getFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml");
			String s = IO.collect(f);
			assertTrue(s.contains("<value>3.2.0-19700101.000000-1</value>"));
			assertTrue(s.contains("<value>3.2.0-19700101.000010-2</value>"));
		}

		//
		// Now try to update it again, should increase build number
		//

		try (Processor context = new Processor();) {
			context.setProperty("-maven-release", "remote;snapshot=20000");
			PutOptions put = new PutOptions();
			put.context = context;

			PutResult r = repo.put(new FileInputStream(jar), put);

			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000020-3.pom", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000020-3.jar", 0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000020-3-sources.jar",
				0);
			assertIsFile(remote,
				"biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/biz.aQute.bnd.maven-3.2.0-19700101.000020-3-javadoc.jar",
				0);
			assertIsFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml", 0);

			File f = IO.getFile(remote, "biz/aQute/bnd/biz.aQute.bnd.maven/3.2.0-SNAPSHOT/maven-metadata.xml");
			String s = IO.collect(f);
			assertTrue(s.contains("<value>3.2.0-19700101.000020-3</value>"));
		}

	}

	private File assertIsFile(File dir, String path, int size) throws IOException {
		File file = IO.getFile(dir, path);
		assertThat(file).as("%s does not exist", path)
			.isFile();
		if (size > 0) {
			assertThat(file).as("Unexpected file size")
				.hasSize(size);
		}
		return file;
	}

	private File assertFileNotExists(File dir, String path) throws IOException {
		File file = IO.getFile(dir, path);
		assertThat(file).as("%s does exist", path).doesNotExist();
		return file;
	}

	void config(Map<String, String> override) throws Exception {
		Processor domain = new Processor();
		HttpClient client = new HttpClient();
		client.retries(0);
		client.setRegistry(domain);
		domain.addBasicPlugin(client);
		config(domain, override);
	}

	void config(Processor domain, Map<String, String> override) throws Exception {
		this.domain = domain;
		Map<String, String> config = new HashMap<>();
		config.put("local", tmp.getAbsolutePath() + "/local");
		config.put("index", tmp.getAbsolutePath() + "/index");
		config.put("snapshotUrl", fnx.getBaseURI() + "/repo/");
		config.put("releaseUrl", fnx.getBaseURI() + "/repo/");

		if (override != null)
			config.putAll(override);

		domain.addBasicPlugin((ProgressPlugin) (name, size) -> {
			System.out.println("Starting " + name);
			return new Task() {

				@Override
				public void worked(int units) {
					System.out.println("Worked " + name + " " + units);
				}

				@Override
				public void done(String message, Throwable e) {
					System.out.println("Done " + name + " " + message + " " + e);
				}

				@Override
				public boolean isCanceled() {
					return false;
				}
			};
		});
		domain.setTrace(true);

		repo = new MavenBndRepository();
		repo.setReporter(domain);
		repo.setRegistry(domain);

		repo.setProperties(config);
	}

	@Test
	public void testPutPlainJarInRepo() throws Exception {

		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		config(map);

		File f = IO.getFile("testdata/plainjar/olingo-odata2-api-1.2.0.jar");
		try (FileInputStream in = new FileInputStream(f)) {
			PutResult put = repo.put(in, null);
		}
		File file = repo.get("org.apache.olingo:olingo-odata2-api", Version.parseVersion("1.2.0"), null);
		assertNotNull(file);
	}

	@Test
	public void testPutPlainJarAndNoMetaInfMaven() throws Exception {

		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		map.put("ignore.metainf.maven", "true");
		config(map);

		File f = IO.getFile("testdata/plainjar/olingo-odata2-api-1.2.0.jar");
		try (FileInputStream in = new FileInputStream(f)) {
			PutResult put = repo.put(in, null);
			System.out.println(put.artifact);
		}
		File file = repo.get("osgi-bundle:olingo-odata2-api", Version.parseVersion("1.2.0"), null);
		assertNotNull(file);
	}

	@Test
	public void testPutBundledNoMetaInfMaven() throws Exception {

		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		map.put("ignore.metainf.maven", "true");
		config(map);

		File f = IO.getFile("testresources/demo.jar");
		try (FileInputStream in = new FileInputStream(f)) {
			PutResult put = repo.put(in, null);
			System.out.println(put.artifact);
		}
		File file = repo.get("osgi-bundle:demo", Version.parseVersion("1.0.0"), null);
		assertNotNull(file);
	}

	@Test
	public void testPutBundledWithMetaInfMaven() throws Exception {

		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		map.put("ignore.metainf.maven", "false");
		config(map);

		File f = IO.getFile("testresources/demo.jar");
		try (FileInputStream in = new FileInputStream(f)) {
			PutResult put = repo.put(in, null);
			System.out.println(put.artifact);
		}
		File file = repo.get("biz.aQute.bnd:demo", Version.parseVersion("1.0.0"), null);
		assertNotNull(file);
	}

	@Test
	public void testPutReleaseWithStaging() throws Exception {
		Workspace ws = new Workspace(IO.getFile("testdata/releasews"));
		Project p1 = ws.getProject("p1");
		Project indexProject = ws.getProject("index");

		Map<String, String> map = new HashMap<>();
		map.put("releaseUrl", remote.toURI()
			.toString());
		map.put("stagingUrl", staging.toURI()
			.toString());
		p1.set("-pom", "true");
		config(ws, map);

		repo.begin(indexProject);
		File jar = IO.getFile("testresources/mavenrepo/org/osgi/org.osgi.dto/1.0.0/org.osgi.dto-1.0.0.jar");
		PutOptions po = new PutOptions();
		po.context = p1;
		PutResult put = repo.put(new FileInputStream(jar), po);
		assertTrue(put.alreadyReleased);

		File demoJar = IO.getFile("testresources/demo.jar");
		PutOptions indexPo = new PutOptions();
		indexPo.context = indexProject;
		put = repo.put(new FileInputStream(demoJar), indexPo);
		assertFalse(put.alreadyReleased);

		repo.end(indexProject);

		assertTrue(indexProject.check());
		assertFalse(IO.getFile(remote, "biz/aQute/bnd/demo/1.0.0/demo-1.0.0-index.xml")
			.isFile());
		assertTrue(IO.getFile(staging, "biz/aQute/bnd/demo/1.0.0/demo-1.0.0-index.xml")
			.isFile());

		assertTrue(IO.getFile(remote, "org/osgi/org.osgi.dto/1.0.0/org.osgi.dto-1.0.0.jar")
			.isFile());
		assertFalse(IO.getFile(staging, "org/osgi/org.osgi.dto/1.0.0/org.osgi.dto-1.0.0.jar")
			.isFile());
	}

}
