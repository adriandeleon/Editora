package com.editora.lsp;
public class JavaLspEvaluationMain {
 public static void main(String[] args) throws Exception {
  var p = new JavaLspEvaluationProbeTest();
  if (args.length > 0 && args[0].equals("repo")) p.evaluateRepositoryJavaSources();
  else p.realJavaFeatureMatrix();
 }
}
