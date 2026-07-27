package com.acme.routesql;

import com.acme.routesql.cli.ScanCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "route-sql-scanner",
    version = "route-sql-scanner 1.0.0",
    subcommands = ScanCommand.class
)
public class Main implements Runnable {
  @Option(names = "--help", usageHelp = true, description = "Show this help message and exit.")
  boolean help;

  @Option(names = "--version", versionHelp = true, description = "Print version information and exit.")
  boolean version;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
