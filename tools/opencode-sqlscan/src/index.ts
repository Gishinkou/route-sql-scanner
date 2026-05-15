import { spawn } from "node:child_process";

export type ScanFormat =
  | "json"
  | "compact-json"
  | "excel"
  | "jsonl"
  | "markdown"
  | "normalized";

export interface ScanRouteSqlInput {
  path: string | string[];
  config?: string;
  jarPath?: string;
  format?: ScanFormat;
  output?: string;
  failedOnly?: boolean;
  timeoutMs?: number;
}

export interface ScanRouteSqlSkillInput {
  projectPath: string;
  configPath: string;
  outputPath?: string;
}

export interface ScanRouteSqlSkillResult {
  outputPath: string;
  format: "xlsx";
}

export async function scanRouteSqlSkill(input: ScanRouteSqlSkillInput): Promise<ScanRouteSqlSkillResult> {
  const outputPath = input.outputPath ?? `${input.projectPath.replace(/[\\/]+$/, "")}/route-sql-diagnostics.xlsx`;
  await scanRouteSql({
    path: input.projectPath,
    config: input.configPath,
    format: "excel",
    output: outputPath,
    failedOnly: true
  });
  return { outputPath, format: "xlsx" };
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
  if (input.output) {
    args.push("--output", input.output);
  }
  if (input.failedOnly) {
    args.push("--failed-only");
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
