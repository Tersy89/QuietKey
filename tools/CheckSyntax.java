import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
public class CheckSyntax {
    public static void main(String[] args) throws Exception {
        List<String> files=new ArrayList<>();try(var paths=Files.walk(Path.of("app/src"))){paths.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toString()));}
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,StandardCharsets.UTF_8)){
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,manager.getJavaFileObjectsFromStrings(files));
            task.parse();for(var d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d.toString());
        }
        System.out.println("Parsed "+files.size()+" Java files without syntax errors. Android type checking requires the Android SDK.");
    }
}
