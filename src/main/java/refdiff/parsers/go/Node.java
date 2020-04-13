package refdiff.parsers.go;

import java.util.ArrayList;
import com.google.gson.annotations.SerializedName;
import refdiff.core.cst.TokenPosition;

public class Node {
	private int id;
	private int start;
	private int end;
	private String name;
	private String type;
	private String parent;
	private String namespace;

	@SerializedName("has_body")
	private boolean hasBody;

	@SerializedName("tokens")
	ArrayList<String> tokens = new ArrayList<>();

	@SerializedName("parameter_names")
	ArrayList<String> parametersNames = new ArrayList<>();

	@SerializedName("parameter_types")
	ArrayList<String> parameterTypes = new ArrayList<>();

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getParent() {
		return parent;
	}

	public void setParent(String parent) {
		this.parent = parent;
	}

	public boolean isHasBody() {
		return hasBody;
	}

	public void setHasBody(boolean hasBody) {
		this.hasBody = hasBody;
	}

	public ArrayList<String> getTokens() {
		return tokens;
	}

	public void setTokens(ArrayList<String> tokens) {
		this.tokens = tokens;
	}

	public ArrayList<String> getParametersNames() {
		return parametersNames;
	}

	public void setParametersNames(ArrayList<String> parametersNames) {
		this.parametersNames = parametersNames;
	}

	public ArrayList<String> getParameterTypes() {
		return parameterTypes;
	}

	public void setParameterTypes(ArrayList<String> parameterTypes) {
		this.parameterTypes = parameterTypes;
	}

	public int getStart() {
		return start;
	}

	public int getEnd() {
		return end;
	}

	public String getName() {
		return name;
	}

	public String getType() {
		return type;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setStart(int start) {
		this.start = start;
	}

	public void setEnd(int end) {
		this.end = end;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setType(String type) {
		this.type = type;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public ArrayList<TokenPosition> getTokenPositions() {
		ArrayList<TokenPosition> positions = new ArrayList<>();
		if (this.tokens == null) {
			return positions;
		}

		for(String token: this.tokens) {
			String[] parts = token.split("-");
			positions.add(new TokenPosition(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
		}

		return positions;
	}

	public String getAddress() {
		return this.namespace+this.name;
	}
}