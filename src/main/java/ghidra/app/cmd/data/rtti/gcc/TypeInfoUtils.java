package ghidra.app.cmd.data.rtti.gcc;

import java.util.List;
import java.util.Set;

import ghidra.program.model.listing.Data;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemBuffer;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.reloc.Relocation;
import ghidra.program.model.reloc.RelocationTable;
import ghidra.program.model.symbol.*;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.AssertException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;
import ghidra.program.model.data.*;
import ghidra.program.util.ProgramMemoryUtil;
import ghidra.program.model.data.DataUtilities.ClearDataMode;
import ghidra.app.cmd.data.rtti.TypeInfo;
import ghidra.app.cmd.data.rtti.gcc.factory.TypeInfoFactory;
import ghidra.app.cmd.data.rtti.gcc.typeinfo.FundamentalTypeInfoModel;
import ghidra.app.cmd.data.rtti.gcc.typeinfo.TypeInfoModel;
import ghidra.app.util.NamespaceUtils;
import ghidra.app.util.demangler.DemangledObject;

import static ghidra.app.util.datatype.microsoft.MSDataTypeUtils.getAbsoluteAddress;
import static ghidra.app.util.demangler.DemanglerUtil.demangle;
import static ghidra.program.model.data.DataUtilities.createData;

public class TypeInfoUtils {

    private TypeInfoUtils() {
    }

    private static Data createString(Program program, Address address) {
        try {
			Integer id = null;
			if (program.getCurrentTransaction() == null) {
				id = program.startTransaction("creating string at "+address.toString());
			}
            DataType dt = new TerminatedStringDataType();
            Data data = createData(
				program, address, dt, -1, false, ClearDataMode.CLEAR_ALL_CONFLICT_DATA);
			if (id != null) {
				program.endTransaction(id, true);
			}
			return data;
        } catch (CodeUnitInsertionException e) {
            return null;
        }
    }

    /**
     * Gets the typename for the {@value TypeInfoModel#STRUCTURE_NAME} at the specified address
     * @param program the program to be searched
     * @param address the address of the TypeInfo Model's DataType
     * @return the TypeInfo's typename string or "" if invalid
     */
    public static String getTypeName(Program program, Address address) {
		int pointerSize = program.getDefaultPointerSize();
        Address nameAddress = getAbsoluteAddress(program, address.add(pointerSize));
        if (nameAddress == null) {
            return "";
        }
        Data data = program.getListing().getDataAt(nameAddress);
        if (data == null) {
            data = createString(program, nameAddress);
        } else if (Undefined.isUndefined(data.getDataType())) {
            data = createString(program, nameAddress);
        }
        if (data == null) {
            return "";
        }
        if (data.hasStringValue()) {
            String result = (String) data.getValue();
            /*
             * Some anonymous namespaces typename strings start with * Unfortunately the *
             * causes issues with demangling so exclude it
             */
            return result.startsWith("*") ? result.substring(1) : result;
        }
        return "";
    }

    /**
     * Locates the TypeInfo with the specified ID_STRING
     * @param program  the program to be searched
     * @param typename the typename of the typeinfo to search for
     * @param monitor  the active task monitor
     * @return the TypeInfo with the corresponding typename or invalid if it doesn't exist
     * @throws CancelledException if the search is cancelled
	 * @see TypeInfoModel#ID_STRING
     */
    public static TypeInfo findTypeInfo(Program program, String typename, TaskMonitor monitor)
        throws CancelledException {
            return findTypeInfo(program, program.getAddressFactory().getAddressSet(),
                                typename, monitor);
    }

    /**
     * Locates the TypeInfo with the specified typename
     * @param program the program to be searched
     * @param set the address set to be searched
     * @param typename the typename to search for
     * @param monitor the active task monitor
     * @return the TypeInfo with the corresponding typename or null if it doesn't exist
     * @throws CancelledException if the search is cancelled
     */
    public static TypeInfo findTypeInfo(Program program, AddressSetView set, String typename,
        TaskMonitor monitor) throws CancelledException {
            int pointerAlignment =
                program.getDataTypeManager().getDataOrganization().getDefaultPointerAlignment();
            List<Address> stringAddress = findTypeString(program, set, typename, monitor);
            if (stringAddress.isEmpty() || stringAddress.size() > 1) {
                return null;
            }
            Set<Address> references = ProgramMemoryUtil.findDirectReferences(program,
                pointerAlignment, stringAddress.get(0), monitor);
            if (references.isEmpty()) {
                return null;
            }
            for (Address reference : references) {
                Address typeinfoAddress = reference.subtract(program.getDefaultPointerSize());
                TypeInfo typeinfo = TypeInfoFactory.getTypeInfo(program, typeinfoAddress);
                if (typeinfo == null) {
                    continue;
                }
				if (typeinfo.getTypeName().equals(typename)) {
					return typeinfo;
				}
            } return null;
    }

    private static List<Address> findTypeString(Program program, AddressSetView set,
        String typename, TaskMonitor monitor) throws CancelledException {
            List<MemoryBlock> dataBlocks = GnuUtils.getAllDataBlocks(program);
            List<Address> typeInfoAddresses =
                ProgramMemoryUtil.findString(typename, program, dataBlocks, set, monitor);
            return typeInfoAddresses;
    }

    private static String relocationToID(Relocation reloc) {
        String baseTypeName = reloc.getSymbolName();
        if (baseTypeName != null) {
            if (baseTypeName.contains("_ZTI")) {
                if (!baseTypeName.contains(TypeInfoModel.STRUCTURE_NAME)) {
                    return FundamentalTypeInfoModel.ID_STRING;
                }
            }
            return baseTypeName.substring(4);
        }
        return null;
    }

    /**
     * Gets the identifier string for the {@value TypeInfoModel#STRUCTURE_NAME}
	 * at the specified address.
     * @param program the program to be searched
     * @param address the address of the TypeInfo Model's DataType
     * @return The TypeInfo's identifier string or "" if invalid
	 * @see TypeInfoModel#ID_STRING
     */
    public static String getIDString(Program program, Address address) {
        RelocationTable table = program.getRelocationTable();
        Relocation reloc = table.getRelocation(address);
        if (reloc != null && reloc.getSymbolName() != null) {
            Address relocationAddress = getAbsoluteAddress(program, address);
            if (relocationAddress == null || relocationAddress.getOffset() == 0) {
                return "";
            }
            MemoryBlock block = program.getMemory().getBlock(relocationAddress);
            if (block == null || !block.isInitialized()) {
                String name = relocationToID(reloc);
                if (name != null) {
                    return name;
                }
            }
        } else {
            Address relocAddress = getAbsoluteAddress(program, address);
            if (relocAddress != null) {
                Data data = program.getListing().getDataContaining(relocAddress);
                if (data != null) {
                    reloc = table.getRelocation(data.getAddress());
                    if (reloc != null) {
                        String name = relocationToID(reloc);
                        if (name != null) {
                            return name;
                        }
                    }
                }
            }
        }
        final int POINTER_SIZE = program.getDefaultPointerSize();
        Address baseVtableAddress = getAbsoluteAddress(program, address);
        if (baseVtableAddress == null || baseVtableAddress.getOffset() == 0) {
            return "";
        }
        Address baseTypeInfoAddress = getAbsoluteAddress(
            program, baseVtableAddress.subtract(POINTER_SIZE));
        if (baseTypeInfoAddress == null) {
            return "";
        }
        return TypeInfoUtils.getTypeName(program, baseTypeInfoAddress);
    }

    /**
     * Checks if a typeinfo* is located at the specified address
     * @param program the program to be searched
     * @param address the address of the suspected pointer
     * @return true if a typeinfo* is present at the address
     */
    public static boolean isTypeInfoPointer(Program program, Address address) {
        Address pointee = getAbsoluteAddress(program, address);
        if (pointee == null) {
            return false;
        }
        return isTypeInfo(program, pointee);
    }

    /**
     * Checks if a typeinfo* is present at the buffer's address
     * @param buf the buffer containing the data
     * @return true if a typeinfo* is present at the buffer's address
     */
    public static boolean isTypeInfoPointer(MemBuffer buf) {
        return buf != null ?
            isTypeInfoPointer(buf.getMemory().getProgram(), buf.getAddress()) : false;
    }

    /**
     * Checks if a valid TypeInfo is located at the address in the program.
     * @param program the program containing the TypeInfo
     * @param address the address of the TypeInfo
     * @return true if the buffer contains a valid TypeInfo
     * @see TypeInfoFactory#isTypeInfo
     */
    public static boolean isTypeInfo(Program program, Address address) {
        /* Makes more sense to have it in this utility, but more convient to check
           if it is valid or not within the factory */
        return TypeInfoFactory.isTypeInfo(program, address);
    }

    /**
     * Checks if a valid TypeInfo is located at the start of the buffer
     * @param buf the memory buffer containing the TypeInfo data
	 * @return true if the buffer contains a valid TypeInfo
     * @see TypeInfoFactory#isTypeInfo
     */
    public static boolean isTypeInfo(MemBuffer buf) {
        return TypeInfoFactory.isTypeInfo(buf);
    }

    /**
     * Gets the Namespace for the corresponding typename
     * @param program the program containing the namespace
     * @param typename the typename corresponding to the namespace
     * @return the Namespace for the corresponding typename
     */
    public static Namespace getNamespaceFromTypeName(Program program, String typename) {
		DemangledObject demangled = typename.startsWith("_ZTI") ?
			demangle(program, typename) : demangle(program, "_ZTI"+typename);
		if (demangled != null) {
			try {
				Integer id = null;
				if (program.getCurrentTransaction() == null) {
					id = program.startTransaction("creating namespace for "+typename);
				}
				Namespace ns = NamespaceUtils.createNamespaceHierarchy(
					demangled.getNamespace().getSignature(),
					null, program, SourceType.ANALYSIS);
				if (id != null) {
					program.endTransaction(id, true);
				}
				return ns;
			} catch (InvalidInputException e) {
				// unexpected
				throw new AssertException(e);
			}
		}
		return null;
    }

    /**
     * Invokes getDataType on the TypeInfo containing the specified typename
     * @param program the program containing the TypeInfo
     * @param typename the type_info class's typename
     * @return the TypeInfo structure for the typename
     * @see TypeInfoFactory#getDataType
     */
    public static Structure getDataType(Program program, String typename) {
        return TypeInfoFactory.getDataType(program, typename);
    }

    /**
     * Retrieves the DataTypePath for the represented datatype
     * @param type the TypeInfo
     * @return the TypeInfo's datatype DataTypePath
     */
    public static DataTypePath getDataTypePath(TypeInfo type) {
        Namespace ns = type.getNamespace().getParentNamespace();
        String path;
        if (ns.isGlobal()) {
            path = "";
        } else {
            path = Namespace.DELIMITER+ns.getName(true);
        }
        path = path.replaceAll(Namespace.DELIMITER, CategoryPath.DELIMITER_STRING);
        return new DataTypePath(path, type.getName());
    }

    /**
	 * Attempts to fetch the TypeInfo instance referenced by the provided relocation
	 * @param program the program containing the relocation
	 * @param reloc the relocation
	 * @return a TypeInfo instance if the relocation can be resolved
	 */
	public static TypeInfo getExternalTypeInfo(Program program, Relocation reloc) {
			Program extProgram = GnuUtils.getExternalProgram(program, reloc);
			if (extProgram != null) {
				SymbolTable table = extProgram.getSymbolTable();
				for (Symbol symbol : table.getSymbols(reloc.getSymbolName())) {
					if (TypeInfoFactory.isTypeInfo(extProgram, symbol.getAddress())) {
						return TypeInfoFactory.getTypeInfo(extProgram, symbol.getAddress());
					}
				}
			}
			return new ExternalClassTypeInfo(program, reloc);
	}

	/**
	 * Generates an appropriate error message for when an invalid type_info is encountered
	 * 
	 * @param program the program containing the data
	 * @param address the address of the data
	 * @param id the expected type_info identification string
	 * @return an appropriate error message
	 */
	public static String getErrorMessage(Program program, Address address, String id) {
		StringBuilder builder = new StringBuilder("Exception caused by Ghidra-Cpp-Class-Analyzer\n");
		builder.append(String.format("The TypeInfo at %s is not valid\n", address));
		builder.append(
			String.format("Expected %s to match identifier %s\n",
						  TypeInfoUtils.getIDString(program, address),
						  id))
			   .append("Potential typename: ")
			   .append(TypeInfoUtils.getTypeName(program, address));
		Relocation reloc = program.getRelocationTable().getRelocation(address);
		if (reloc != null) {
			builder.append(String.format(
				"\nrelocation at %s to symbol %s", reloc.getAddress(), reloc.getSymbolName()));
		}
		return builder.toString();
	}

}
