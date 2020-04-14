package refdiff.parsers.go;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import com.eclipsesource.v8.NodeJS;
import com.eclipsesource.v8.V8Object;
import com.google.gson.GsonBuilder;

import refdiff.core.cst.*;
import refdiff.core.io.FilePathFilter;
import refdiff.core.io.SourceFile;
import refdiff.core.io.SourceFileSet;
import refdiff.parsers.LanguagePlugin;

public class GoPlugin implements LanguagePlugin, Closeable {
	private File tempDir = null;

	public GoPlugin() throws Exception {}

	public GoPlugin(File tempDir) {
		this.tempDir = tempDir;
	}

	public Node[] execParser(String rootFolder, String path) throws IOException {
		Runtime rt = Runtime.getRuntime();
//		String parserPath = getClass().getClassLoader().getResource("parser").getFile(); TODO: check resource in build
		String parserPath = "/home/rodrigo/development/go-ast-parser/parser";
		String[] commands = { parserPath, "-directory", rootFolder, "-file", path };
		
		Process proc = rt.exec(commands);
		BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
		String content = stdInput.lines().collect(Collectors.joining());

		return new GsonBuilder().create().fromJson(content, Node[].class);
	}

	private void updateChildrenNodes(Map<String, CstNode> nodeByAddress, Map<String, HashSet<String>> childrenByAddress) {
		for (Map.Entry<String, HashSet<String>> parent : childrenByAddress.entrySet()) {
			if (!nodeByAddress.containsKey(parent.getKey())) {
				throw new RuntimeException("node not found: " + parent.getKey());
			}

			CstNode parentNode = nodeByAddress.get(parent.getKey());
			for (String childAddress: parent.getValue()) {
				if (!nodeByAddress.containsKey(childAddress)) {
					throw new RuntimeException("node not found: " + childAddress);
				}
				parentNode.addNode(nodeByAddress.get(childAddress));
			}
		}
	}

	@Override
	public CstRoot parse(SourceFileSet sources) throws Exception {
		Optional<Path> optBasePath = sources.getBasePath();
		Map<String, CstNode> nodeByAddress = new HashMap<>();
		Map<String, HashSet<String>> childrenByAddress = new HashMap<>();

		if (!optBasePath.isPresent()) {
			if (this.tempDir == null) {
				throw new RuntimeException("The GoParser requires a SourceFileSet that is materialized on the file system. Either pass a tempDir to GoParser's contructor or call SourceFileSet::materializeAt before calling this method.");
			} else {
				sources.materializeAtBase(tempDir.toPath());
				optBasePath = sources.getBasePath();
			}
		}

		File rootFolder = optBasePath.get().toFile();

		try {
			CstRoot root = new CstRoot();
			int nodeCounter = 1;

			for (SourceFile sourceFile : sources.getSourceFiles()) {
				Node[] astNodes = this.execParser(rootFolder.toString(), sourceFile.getPath());
				for (Node node : astNodes) {

					node.setId(nodeCounter);
					nodeCounter++;

					if (node.getType().equals(NodeType.FILE)) {
						ArrayList<TokenPosition> tokens = node.getTokenPositions();
						TokenizedSource tokenizedSource = new TokenizedSource(sourceFile.getPath(), tokens);
						root.addTokenizedFile(tokenizedSource);
					}

					CstNode cstNode = toCSTNode(node, sourceFile.getPath());

					// save parent information
					nodeByAddress.put(node.getAddress(), cstNode);
					if (node.getParent() != null) {
						// initialize if key not present
						if (!childrenByAddress.containsKey(node.getParent())) {
							childrenByAddress.put(node.getParent(), new HashSet<>());
						}

						childrenByAddress.get(node.getParent()).add(node.getAddress());
					}

					root.addNode(cstNode);
				}
			}
			updateChildrenNodes(nodeByAddress, childrenByAddress);

			return root;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private CstNode toCSTNode(Node node, String fileName) {
		CstNode cstNode = new CstNode(node.getId());
		cstNode.setType(node.getType());
		cstNode.setSimpleName(node.getName());
		cstNode.setNamespace(node.getNamespace());
		cstNode.setLocation(new Location(fileName, node.getStart(), node.getEnd(), node.getLine()));

		// TODO: check TYPE_MEMBER use
		if (node.getType().equals(NodeType.FUNCTION)) {
			cstNode.getStereotypes().add(Stereotype.TYPE_MEMBER);
		}

		if (node.hasBody()) {
			cstNode.getStereotypes().add(Stereotype.HAS_BODY);
		} else {
			cstNode.getStereotypes().add(Stereotype.ABSTRACT);
		}

		if (node.getParametersNames() != null && !node.getParametersNames().isEmpty()) {
			List<Parameter> parameters = new ArrayList<>();
			for (String name : node.getParametersNames()) {
				parameters.add(new Parameter(name));
			}			
			cstNode.setParameters(parameters);
		}
		
		if (node.getType().equals(NodeType.FUNCTION)) {
			String localName = String.format("%s(%s)", node.getName(), String.join(",", node.getParameterTypes()));
			if (node.getReceiver() != null && !node.getReceiver().isEmpty()) {
				localName = String.format("%s.%s", node.getReceiver(), localName);
			}
			cstNode.setLocalName(localName);
		} else {
			cstNode.setLocalName(node.getName());
		}
		

		return cstNode;
	}

	@Override
	public FilePathFilter getAllowedFilesFilter() {
		return new FilePathFilter(Arrays.asList(".go"), Arrays.asList("_test.go"));
	}

	@Override
	public void close() throws IOException {}
}
