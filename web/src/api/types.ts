// Mirrors of the control-plane API records (see docs/api.md).

export interface AuthStatus {
  setupRequired: boolean
  authenticated: boolean
  username: string | null
  stepUpValidUntil: string | null
  recoveryCodesLeft: number | null
}

export interface DiskUsage { label: string; path: string; totalBytes: number; freeBytes: number }
export interface HostFacts {
  hostname: string; os: string; kernel: string; cpuCores: number; memTotalBytes: number; swapTotalBytes: number
  dockerVersion: string; disks: DiskUsage[]; filesystems: string[]
}
export interface HostMetrics {
  timestamp: string; cpuPercent: number; load1: number; memTotalBytes: number; memAvailableBytes: number
  swapUsedBytes: number; diskReadBytesPerSec: number; diskWriteBytesPerSec: number; disks: DiskUsage[]
}
export interface Server {
  id: string; name: string; agentUrl: string; hostname: string | null; os: string | null; kernel: string | null
  cpuCores: number | null; memTotalBytes: number | null; dockerVersion: string | null; lastSeenAt: string | null
}
export interface ServerStatus { server: Server; agentReachable: boolean; facts: HostFacts | null; metrics: HostMetrics | null; error: string | null }

export interface ContainerInfo {
  id: string; name: string; image: string; state: string; health: string | null; status: string; managed: boolean
  instanceId: string | null; pgMajor: number | null; pgVersion: string | null; libc: 'GLIBC' | 'MUSL' | 'UNKNOWN' | null
  networks: string[]; labels: Record<string, string>; memoryLimitBytes: number | null
}
export interface DiscoveredContainer { container: ContainerInfo; registered: boolean }

export type InstanceKind = 'MANAGED' | 'EXTERNAL'
export type Protection = 'PRODUCTION' | 'STANDARD' | 'EPHEMERAL'
export type ExternalMode = 'MONITOR_ONLY' | 'MANAGE_OBJECTS'
export type Exposure = 'NONE' | 'LOCALHOST' | 'PUBLIC'

export interface Instance {
  id: string; serverId: string; name: string; kind: InstanceKind; status: string; statusMessage: string | null
  pgMajor: number | null; pgVersion: string | null; image: string | null; libc: string | null; containerName: string | null
  host: string; port: number; sslMode: string; bindAddress: string | null; hostPort: number | null; extraNetworks: string[]
  externalMode: ExternalMode | null; protection: Protection; memoryMb: number | null; cpus: number | null
  settings: Record<string, string>; adminRole: string; objectsWritable: boolean; createdAt: string
}

export interface IssuedCredential { role: string; kind: string; password: string }
export interface Endpoint { kind: string; description: string; host: string; port: number; sslMode: string; uri: string }
export interface ConnectionInfo { database: string; role: string; passwordStored: boolean; endpoints: Endpoint[]; caCertificateUrl: string | null }

export interface CreateInstanceResult { instance: Instance; jobId: string; credentials: IssuedCredential[] }

export interface DatabaseView {
  name: string; owner: string; sizeBytes: number; connections: number; allowConnections: boolean; encoding: string
  collation: string; managed: boolean; system: boolean
}
export interface Extension { name: string; version: string; schema: string }
export interface DatabaseDetails { database: DatabaseView; extensions: Extension[]; loginRoles: string[]; extensionsError: string | null }
export interface CreateDatabaseResult { jobId: string; database: string; credentials: IssuedCredential[]; connection: ConnectionInfo }

export type Access = 'READ_ONLY' | 'READ_WRITE'
export interface RoleView {
  name: string; canLogin: boolean; superuser: boolean; createDb: boolean; createRole: boolean; memberOf: string[]
  databases: string[]; managed: boolean; kind: string | null; locked: boolean; passwordStored: boolean
  rotatedAt: string | null; protectedRole: boolean
}
export interface PasswordResult { role: string; password: string; connections: ConnectionInfo[] }
export interface Dependency { database: string; kind: string; catalog: string; count: number }

export interface Job {
  id: string; type: string; status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'; resourceKey: string; title: string
  progress: number; message: string | null; error: string | null; attempts: number; createdBy: string
  createdAt: string; startedAt: string | null; finishedAt: string | null; result: Record<string, unknown> | null
}
export interface JobEvent { id: number; ts: string; level: string; message: string }
export interface JobDetails { job: Job; events: JobEvent[] }
export interface Submitted { jobId: string | null }

export interface AuditEntry {
  id: number; ts: string; actor: string; sourceIp: string | null; action: string; targetType: string | null
  targetId: string | null; outcome: string; details: Record<string, unknown>
}

export interface Warning { severity: 'CRITICAL' | 'WARNING' | 'INFO'; source: string; message: string }
export interface Dashboard {
  servers: ServerStatus[]; instances: number; instancesByStatus: Record<string, number>; managedDatabases: number
  recentJobs: Job[]; warnings: Warning[]
}
export interface Info { publicHost: string | null; sqlEditorUrl: string | null; supportedPgMajors: number[]; settingsAllowlist: Record<string, string> }

export interface Session {
  pid: number; database: string | null; user: string | null; application: string | null; client: string | null
  state: string | null; waitEventType: string | null; waitEvent: string | null; querySeconds: number | null
  transactionSeconds: number | null; query: string | null
}
export interface BlockedSession { pid: number; user: string; database: string; seconds: number | null; blockedBy: number[]; query: string }
export interface DatabaseStats {
  database: string; backends: number; commits: number; rollbacks: number; cacheHitRatio: number
  tuplesInserted: number; tuplesUpdated: number; tuplesDeleted: number; deadlocks: number; tempBytes: number
}
export interface Overview {
  version: string; uptimeSeconds: number; maxConnections: number; connections: number
  connectionsByState: Record<string, number>; longRunningQueries: number; blockedSessions: number; replicas: number
  archiver: { archivedCount: number; lastArchivedAt: string | null; failedCount: number; lastFailedAt: string | null; archiveMode: string }
  databases: DatabaseStats[]
}
