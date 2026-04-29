import { spawn } from "node:child_process";

export type ScanFormat = "json" | "jsonl" | "markdown";

export interface ScanRouteSqlInput {
  path: string | string[];
  config?: string;
  jarPath?: string;
  format?: ScanFormat;
  timeoutMs?: number;
}

export async function scanRouteSql(input: ScanRouteSqlInput): Promise<string> {
  const jarPath = input.jarPath ?? "target/route-sql-scanner-0.1.0.jar";
  const format = input.format ?? "json";
  const paths = Array.isArray(input.path) ? input.path : [input.path];
  const args = ["-jar", jarPath, "scan", "--format", format, "--fail-on", "NEVER"];
  for (const path of paths) {
    args.push("--path", path);
  }
  if (input.config) {
    args.push("--config", input.config);
  }

  return new Promise((resolve, reject) => {
    const child = spawn("java", args, { stdio: ["ignore", "pipe", "pipe"] });
    const timer = setTimeout(() => {
      child.kill("SIGTERM");
      reject(new Error(`route-sql-scanner timed out after ${input.timeoutMs ?? 60000}ms`));
    }, input.timeoutMs ?? 60000);

    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", chunk => {
      stdout += chunk;
    });
    child.stderr.on("data", chunk => {
      stderr += chunk;
    });
    child.on("error", error => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", code => {
      clearTimeout(timer);
      if (code === 0) {
        resolve(stdout);
      } else {
        reject(new Error(`route-sql-scanner exited with ${code}: ${stderr}`));
      }
    });
  });
}
