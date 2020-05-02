package refdiff.parsers.go;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import refdiff.core.cst.*;
import refdiff.core.io.FilePathFilter;
import refdiff.core.io.SourceFile;
import refdiff.core.io.SourceFileSet;
import refdiff.parsers.LanguagePlugin;

import com.fasterxml.jackson.databind.ObjectMapper;

public class GoPlugin implements LanguagePlugin, Closeable {
	private File tempDir = null;

	public GoPlugin() throws Exception {}

	public GoPlugin(File tempDir) {
		this.tempDir = tempDir;
	}

	public Node[] execParser(String rootFolder, String path) throws IOException {
		ProcessBuilder builder = new ProcessBuilder("/home/rodrigo/development/go-ast-parser/parser",
				"-directory", rootFolder, "-file", path);
		Process proc = builder.start();

		System.out.println("Parsing: "+path);

		Node[] nodes = new Node[0];
		try {
			ObjectMapper mapper = new ObjectMapper();
			nodes = mapper.readValue(proc.getInputStream(), Node[].class);
		} catch (Exception e) {
			String errors = new BufferedReader(new InputStreamReader(proc.getErrorStream()))
					.lines().collect(Collectors.joining("\n"));
			if (errors.length() > 0) {
				throw new RuntimeException(errors);
			}
			e.printStackTrace();
		}

		return nodes;
	}

	private void updateChildrenNodes(CstRoot root, Map<String, CstNode> nodeByAddress, Map<String, CstNode> fallbackByAddress,
									 Map<String, HashSet<String>> childrenByAddress) {

		for (Map.Entry<String, HashSet<String>> parent : childrenByAddress.entrySet()) {
			if (!nodeByAddress.containsKey(parent.getKey())) {

				// check nodes in fallbacks and add to root
				CstNode fallbackNode = fallbackByAddress.get(parent.getKey());
				if (fallbackNode == null) {
//					throw new RuntimeException("node not found: " + parent.getKey());
					System.err.println("NODE NOT FOUND: " + parent.getKey());
					continue;
				}
				nodeByAddress.put(parent.getKey(), fallbackNode);
				root.addNode(fallbackNode);
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

	private void updateFunctionCalls(CstRoot root, Map<String, CstNode> nodeByAddress, Map<String, CstNode> fallbackByAddress,
									 Map<String, HashSet<String>> functionCalls) {

		for (Map.Entry<String, HashSet<String>> node : functionCalls.entrySet()) {
			if (!nodeByAddress.containsKey(node.getKey())) {
				throw new RuntimeException("node not found: " + node.getKey());
			}

			CstNode caller = nodeByAddress.get(node.getKey());
			for (String functionCall: node.getValue()) {
				if (!nodeByAddress.containsKey(functionCall)) {
					CstNode fallbackNode = fallbackByAddress.get(functionCall);
					if (fallbackNode == null) {
						continue;
					}
					nodeByAddress.put(functionCall, fallbackNode);
					root.addNode(fallbackNode);
				}
				root.getRelationships().add(new CstNodeRelationship(CstNodeRelationshipType.USE, caller.getId(),
						nodeByAddress.get(functionCall).getId()));
			}
		}
	}

	private TokenizedSource tokenizeSourceFile(Node node, SourceFileSet sources, SourceFile sourceFile) throws IOException {
		ArrayList<TokenPosition> tokens = node.getTokenPositions(sources.readContent(sourceFile));
		return new TokenizedSource(sourceFile.getPath(), tokens);
	}

	private boolean isValidGoFile(String path) {
		return path.endsWith(".go") && !path.startsWith("vendor/") && !path.endsWith("_test.go") &&
				!path.endsWith("generated.pb.go") && !path.endsWith("_gen.go") && !path.endsWith("_generated.go") &&
				!path.endsWith(".generated.go") && !path.matches("generated\\..+\\.go$");
	}

	@Override
	public CstRoot parse(SourceFileSet sources) throws Exception {
		Optional<Path> optBasePath = sources.getBasePath();
		Map<String, CstNode> nodeByAddress = new HashMap<>();
		Map<String, CstNode> fallbackByAddress = new HashMap<>();
		Map<String, HashSet<String>> childrenByAddress = new HashMap<>();
		Map<String, HashSet<String>> functionCalls = new HashMap<>();
		List<SourceFile> additionalFiles = new ArrayList<>();
		List<SourceFile> sourceFiles = new ArrayList<>();

		for (SourceFile sourceFile : sources.getSourceFiles()) {
			if (!isValidGoFile(sourceFile.getPath())) {
				continue;
			}

			sourceFiles.add(sourceFile);

			File parent = new File(sourceFile.getPath()).getParentFile();
			String sourceFolder = "";
			if (parent != null) {
				sourceFolder = parent.getPath();
			}

			for (SourceFile file : sources.getFilesFromPath(Paths.get(sourceFolder))) {
				if (!isValidGoFile(file.getPath())) {
					continue;
				}

				additionalFiles.add(file);
			}
		}

		sources.getSourceFiles().addAll(additionalFiles);

		if (!optBasePath.isPresent()) {
			if (this.tempDir == null) {
				throw new RuntimeException("The GoParser requires a SourceFileSet that is materialized on the file system. " +
						"Either pass a tempDir to GoParser's contructor or call SourceFileSet::materializeAt before calling this method.");
			} else {
				sources.materializeAtBase(tempDir.toPath());
				optBasePath = sources.getBasePath();
			}
		}

		File rootFolder = optBasePath.get().toFile();

		try {
			CstRoot root = new CstRoot();
			Map<String, Boolean> fileProcessed = new HashMap<>();
			int nodeCounter = 1;

			for (SourceFile sourceFile : sourceFiles) {
				fileProcessed.put(sourceFile.getPath(), true);
				Node[] astNodes = this.execParser(rootFolder.toString(), sourceFile.getPath());
				for (Node node : astNodes) {
					node.setId(nodeCounter++);

					if (node.getType().equals(NodeType.FILE)) {
						root.addTokenizedFile(tokenizeSourceFile(node, sources, sourceFile));
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

					// save call graph information
					if (node.getType().equals(NodeType.FUNCTION) && node.getFunctionCalls() != null) {
						// initialize if key not present
						if (!functionCalls.containsKey(node.getAddress())) {
							functionCalls.put(node.getAddress(), new HashSet<>());
						}
						functionCalls.get(node.getAddress()).addAll(node.getFunctionCalls());
					}

					root.addNode(cstNode);
				}
			}

			for (SourceFile sourceFile: additionalFiles) {
				if (fileProcessed.getOrDefault(sourceFile.getPath(), false)) { // avoid duplicate parser
					continue;
				}

				fileProcessed.put(sourceFile.getPath(), true);
				Node[] astNodes = this.execParser(rootFolder.toString(), sourceFile.getPath());
				for (Node node : astNodes) {
					node.setId(nodeCounter++);

					if (node.getType().equals(NodeType.FILE)) {
						root.addTokenizedFile(tokenizeSourceFile(node, sources, sourceFile));
					}

					CstNode cstNode = toCSTNode(node, sourceFile.getPath());
					fallbackByAddress.put(node.getAddress(), cstNode);
				}
			}

			updateChildrenNodes(root, nodeByAddress, fallbackByAddress, childrenByAddress);
			updateFunctionCalls(root, nodeByAddress, fallbackByAddress, functionCalls);

			return root;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private CstNode toCSTNode(Node node, String filePath) {
		CstNode cstNode = new CstNode(node.getId());
		cstNode.setType(node.getType());
		cstNode.setSimpleName(node.getName());
		cstNode.setNamespace(node.getNamespace());
		cstNode.setLocation(new Location(filePath, node.getStart(), node.getEnd(), node.getLine()));

		if (node.getType().equals(NodeType.STRUCT)) {
			cstNode.getStereotypes().add(Stereotype.TYPE_MEMBER);
		}

		if (node.hasBody()) {
			cstNode.getStereotypes().add(Stereotype.HAS_BODY);
		} else if(node.getType().equals(NodeType.FUNCTION) || node.getType().equals(NodeType.STRUCT))  {
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
		return new FilePathFilter(Arrays.asList(".go"), Arrays.asList("_test.go", "gen.go"));
	}

	@Override
	public void close() {}
}
