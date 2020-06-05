package ghidra.app.plugin.prototype.CppCodeAnalyzerPlugin.wrappers;

import java.util.Map;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.Rtti1Model;
import ghidra.app.cmd.data.rtti.Rtti3Model;

public interface WindowsClassTypeInfo extends ClassTypeInfo {
	public static final String PURE_VIRTUAL_FUNCTION_NAME = "_purecall";

	public Rtti1Model getBaseModel();
	public Map<ClassTypeInfo, Integer> getBaseOffsets();
	public Rtti3Model getHierarchyDescriptor();
	
}