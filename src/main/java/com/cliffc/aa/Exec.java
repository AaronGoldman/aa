package com.cliffc.aa;

/** an implementation of language AA
 */

public abstract class Exec {
  public static TypeEnv go( String src, String str ) { // Execute string
    Env env = Env.top();
    Parse p = new Parse(src,env,str);
    Node n = p.go();
    return new TypeEnv(p._gvn.type(n)/*pessimistic type*/,env);
  }
}
