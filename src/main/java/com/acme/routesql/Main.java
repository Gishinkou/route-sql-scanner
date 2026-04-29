package com.acme.routesql;

import com.acme.routesql.cli.ScanCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "route-sql-scanner",
    mixinStandardHelpOptions = true,
    version = "route-sql-scanner 0.1.0",
    subcommands = ScanCommand.class
)
public class Main implements Runnable {
  public static void main(String[] args) {
    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
