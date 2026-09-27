import org.lwjgl.util.shaderc.Shaderc;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ShaderCompiler {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ShaderCompiler <source.[comp|frag|vert]> <output.spv>");
            System.exit(1);
        }
        String sourcePath = args[0];
        String outputPath = args[1];
        String source = Files.readString(Paths.get(sourcePath));

        int shaderKind = Shaderc.shaderc_compute_shader;
        if (sourcePath.endsWith(".frag") || sourcePath.endsWith(".fsh")) {
            shaderKind = Shaderc.shaderc_fragment_shader;
        } else if (sourcePath.endsWith(".vert") || sourcePath.endsWith(".vsh")) {
            shaderKind = Shaderc.shaderc_vertex_shader;
        }

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_3);
        Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);

        long result = Shaderc.shaderc_compile_into_spv(compiler, source, shaderKind, sourcePath, "main", options);
        int status = Shaderc.shaderc_result_get_compilation_status(result);
        if (status != Shaderc.shaderc_compilation_status_success) {
            String err = Shaderc.shaderc_result_get_error_message(result);
            System.err.println("Compilation error in " + sourcePath + ":\n" + err);
            System.exit(2);
        }

        ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
        byte[] data = new byte[bytes.remaining()];
        bytes.get(data);
        Files.write(Paths.get(outputPath), data);

        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
        System.out.println("Compiled " + sourcePath + " -> " + outputPath + " (" + data.length + " bytes)");
    }
}
