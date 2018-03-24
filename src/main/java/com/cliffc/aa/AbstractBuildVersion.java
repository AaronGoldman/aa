package com.cliffc.aa;

public abstract class AbstractBuildVersion {
  abstract public String branchName();
  abstract public String lastCommitHash();
  abstract public String describe();
  abstract public String projectVersion();
  abstract public String compiledOn();
  abstract public String compiledBy();
}
