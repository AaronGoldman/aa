package com.cliffc.aa;

/** an implementation of language AA
 */

// A basic implementation of 'eval'.
// TODO: rename class

public abstract class Exec {
  // Parse and type a file-level string.  Reset back to Env.<clinit> when done.
  // Suitable for repeated tests
  public static TypeEnv file( String src, String str, int rseed, boolean do_gcp, boolean do_hmt ) { // Execute string

    TypeEnv te = go(Env.TOP,src,str,rseed,do_gcp,do_hmt);

    // Kill, cleanup and reset for another parse
    te._scope.unhook();   // The exiting scope is removed
    // All edges removed, otherwise a self-cycle keeps alive
    while( te._scope.len()>0 ) te._scope.pop();
    Env.top_reset();                   // Hard reset

    return te;
  }

  // Parse and type a string.  Can be nested.  In theory, will be eval() someday.
  // In theory, can keep the result node and promote them for the REPL.
  public static TypeEnv go( Env top, String src, String str, int rseed, boolean do_gcp, boolean do_hmt ) { // Execute string
    AA.RSEED = rseed;
    AA.DO_GCP = do_gcp;
    AA.DO_HMT = do_hmt;
    Env e = Env.FILE = new Env(top,null,false,top._scope.ctrl(),top._scope.mem());

    // Parse a program
    ErrMsg err = new Parse(src,false,e,str).prog();

    // Close file scope; no program text in this file, so no more fields to add.
    e._scope.keep();
    Env.GVN.add_flow_uses(e._scope);// Post-parse, revisit top-level called functions
    e.close();                // No more fields added to the parse scope
    
    // Pessimistic optimizations; might improve error situation
    Env.GVN.iter();
    
    Env.pre_combo();   // Remove all the things kept alive until Combo runs
    Combo.opto();      // Global Constant Propagation and Hindley-Milner Typing
    
    Env.GVN.iter(); // Re-check all ideal calls now that types have been maximally lifted
    
    Env.FILE=null;

    return e.gather_errors(err);
  }


  public static String dump() { return Env.START.dumprpo(false,false); } // Debugging hook
}
