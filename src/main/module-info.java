/**
 * @jenesis.main org.tiger.java.code.formater.JavaCodeFomatter
 */
module org.tiger.code.fomatter {
    requires transitive java.compiler;
    requires jdk.compiler;

    exports org.tiger.java.code.formater;
}