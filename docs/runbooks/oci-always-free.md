# Runbook: deploy on Oracle Cloud (OCI) Always Free Ampere A1

Applies to ADR 0041 (single host, Docker Compose, Caddy) when the host is an OCI Ampere A1 VM (arm64). The general procedure is
`docs/runbooks/deploy.md`; this page covers only what is specific to OCI and to arm64. Owner decisions are in
`docs/launch-checklist.md`.

**How to read the facts.** Statements marked **[doc]** were read on Oracle's official documentation on 2026-10-08 and the page is
quoted. Statements marked **[verify in the console]** are plausible or commonly reported but were not confirmed in a document, so
check them in the OCI console (or the linked page) before relying on them. Nothing here was exercised on a real OCI account:
the arm64 images and the backup settings were exercised locally (see "What was tested").

## 0. Read this first: the free allowance is 2 OCPU / 12 GB of A1 in total

**The option that stays free: one production VM of 2 OCPU / 12 GB, no staging VM on OCI (option A).** Everything else in this section is about
why, and what it costs to go beyond. Confirm the numbers in the console (Governance > Limits, Quotas and Usage) **[verify in the console]**: they are read
from documentation, not from your tenancy.

The plan was one production VM of about 3 OCPU / 16 GB and one staging VM of about 1 OCPU / 6-8 GB (4 OCPU / 22-24 GB in total).
Oracle's current Always Free page says:

> "All tenancies get the first 1,500 OCPU hours and 9,000 GB hours per month for free for VM instances using the
> VM.Standard.A1.Flex shape ... For Always Free tenancies, this is equivalent to 2 OCPUs and 12 GB of memory."
> "You can use all of the Always Free OCPUs and memory to create a single instance, or create up to two instances of 1 OCPU each."

[doc] https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm (read 2026-10-08). The page does not contain the
older figures of 4 OCPUs / 24 GB. So the planned split is **twice what is free**; on a Pay As You Go tenancy the excess is billed
("Oracle ... will only charge you for resource usage above the Always Free limits", same page). Check the numbers that apply to
your tenancy under Governance > Limits, Quotas and Usage **[verify in the console]** before creating anything, then pick one:

| Option | Production VM | Staging VM | Cost |
|---|---|---|---|
| A. Free only (recommended) | 2 OCPU / 12 GB (the whole allowance) | none on OCI: use `infra/deploy/rehearsal.sh` locally and the CI image builds; deploy to production with a tag only after the rehearsal passes | 0 |
| B. As planned | 3 OCPU / 16 GB | 1 OCPU / 8 GB (`infra/deploy/env.staging-small.example`) | the part above 2 OCPU / 12 GB is billed at the A1 rate in the Oracle price list **[verify in the console / price list]**; set a budget alert |
| C. Split the free allowance | 1 OCPU / 8 GB | 1 OCPU / 4 GB | 0, but production is then too small for the default limits (see section 6): not recommended |

Everything below works for A and B. Production on option A uses the default limits unchanged (about 7.2 GiB of limits on 12 GB).

## 1. Account setup

- **Home region.** Choose it carefully at sign-up: "You can provision Always Free Autonomous AI Databases and compute instances
  only in the home region" [doc] https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm . That Always Free A1 instances must be created in the
  home region is also stated on the Always Free page. That the home region **cannot be changed later** is widely reported and
  matches Oracle's sign-up flow, but it is not stated on the pages read: treat it as permanent **[verify in the console]**.
  Trial, free tier and Pay As You Go tenancies are limited to one subscribed region [doc]
  https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm .
- **Which region for Nigeria.** Regions listed on the regions page [doc]: South Africa Central (Johannesburg) `af-johannesburg-1`
  (1 availability domain), Germany Central (Frankfurt) `eu-frankfurt-1` (3 availability domains), UK South (London) `uk-london-1`
  (3), Netherlands Northwest (Amsterdam) `eu-amsterdam-1` (1), France Central (Paris) `eu-paris-1` (1), Switzerland North
  (Zurich) `eu-zurich-1` (1) and others. Johannesburg is the nearest to Nigeria by distance; Frankfurt and London have three
  availability domains each, which matters for the "out of capacity" problem in section 3. Round-trip times from Nigeria were
  not measured: test both with `ping`/`mtr` from the owner's network and from a Nigerian mobile network if you can. Whether A1
  shapes are offered in a region is shown in the console when you create the instance **[verify in the console]**.
- **Verification card.** "For security purposes, most users need a mobile phone number and a credit card to create an account.
  Your credit card will not be charged unless you upgrade your account." [doc] freetier.htm above. A USD virtual card from a Nigerian
  bank or fintech is often what works for an account that is billed in USD; Oracle does not document which issuers or prepaid
  cards it accepts, so this is a tip, not a guarantee **[verify]**. Use a name and address that match the card.
- **Free Trial.** "$300 of cloud credits that are valid for up to 30 days"; "Paid resources that were provisioned with your credits
  during your free trial are reclaimed by Oracle unless you upgrade your account" [doc] freetier.htm. Always Free resources are
  not affected when the trial ends.
- **Upgrade the production tenancy to Pay As You Go.** Console path [doc] https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/changingpaymentmethod.htm :
  Billing & Cost Management > Upgrade and Manage Payment > Pay As You Go; you must be in the Administrators group; the card is
  authorized for $100 USD (or equivalent) and the authorization is reversed; "The upgrade can take a day or two to complete".
  Reasons: Oracle's own note on "out of host capacity" says upgrading "gives you access to more types of Compute resources" and
  "Oracle doesn't charge for Always Free resources after you upgrade, and will only charge you for resource usage above the
  Always Free limits" [doc] Always Free page. **Idle reclaim:** the page says "Idle Always Free compute instances may be reclaimed by
  Oracle" (see section 8) and does **not** say whether a Pay As You Go tenancy is exempt. Do not assume it is; keep the instance
  busy enough (it will be: Postgres, the JVM and ClamAV hold far more than 20% of the memory) and monitor it.
  Create a budget with an alert before you create any paid-size resource (Billing & Cost Management > Budgets) **[verify in the console]**.
- **Do not create extra free accounts to multiply the free resources.** Oracle's terms may prohibit multiple free accounts and
  Oracle can terminate accounts for it; none of the pages read says either way, and the terms were not read. The owner must read
  the current Oracle Cloud Services terms and the Free Tier terms before opening a second account **[verify: owner reads the
  terms]**. The second account/tenancy in this guide is for **offsite backups only** (section 7), not for compute. If the owner
  concludes the second account is not allowed, use another provider's bucket (Cloudflare R2, Backblaze B2, any S3-compatible
  store): only `BACKUP_S3_*` change.

## 2. Network: VCN, subnet, internet gateway, rules

Use the VCN wizard "Create VCN with Internet Connectivity" (console: Networking > Virtual cloud networks) **[verify in the console]**;
it creates a VCN, a public subnet, an internet gateway, a route table with the default route to the gateway and a default
security list. Then:

1. **Reserve a public IP** for the VM (or at least keep the ephemeral one for the VM's whole life) so the DNS record stays valid.
   Whether a reserved IP is free for the Always Free tenancy was not confirmed **[verify in the console / price list]**.
2. **Ingress rules** (security list of the public subnet, or a network security group on the VM's VNIC; Oracle describes both:
   "Security list rules function the same as network security group rules", and security lists apply to every VNIC in the
   subnet [doc] https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/securitylists.htm ; an NSG "provides a virtual firewall
   for a set of cloud resources that all have the same security posture", is initially empty, and its rule source can be a CIDR
   or another NSG [doc] https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/networksecuritygroups.htm ). Prefer an NSG
   (`jobfinder-web`) so production and staging can differ:

   | Source | Protocol | Destination port | Why |
   |---|---|---|---|
   | `0.0.0.0/0` (and `::/0` if you use IPv6) | TCP | 80 | HTTP, ACME challenge, redirect to HTTPS |
   | `0.0.0.0/0` (and `::/0`) | TCP | 443 | HTTPS |
   | `0.0.0.0/0` (and `::/0`) | UDP | 443 | HTTP/3 (Caddy publishes it) |
   | owner IP `/32` | TCP | 22 | SSH |

   Rules are stateful by default, so replies are allowed [doc] securitylists.htm. The default security list ships with
   "Allow TCP traffic on destination port 22 (SSH) from authorized source IP addresses and any source port" and Oracle
   recommends changing the source to authorized addresses [doc]. Remove the `0.0.0.0/0` rule for 22 once your own address is in.
3. **SSH and the deploy workflow.** The GitHub workflow connects over SSH from GitHub-hosted runners, whose addresses change.
   If port 22 is limited to the owner's IP, the workflow cannot reach the VM. Choose one: (a) keep 22 open to the world with
   key-only authentication, `PasswordAuthentication no`, `PermitRootLogin no` and fail2ban (the key lives only in the GitHub
   environment secret, see `deploy.md`); (b) keep 22 closed and deploy by hand from the owner's machine with
   `infra/deploy/deploy.sh`; (c) a self-hosted runner. This guide does not decide for you; the checklist asks the owner to.
4. **The VM's own firewall.** A security list/NSG is not enough: platform images also carry host firewall rules.
   - **Ubuntu** images: Oracle says "Do not use UFW to edit firewall rules. Platform images are preconfigured with firewall rules
     to enable instances to make outgoing connections to the instance's boot and block volumes ... UFW may remove these rules so
     that during a reboot the instance is not able to connect to the boot and block volumes. To modify or add new firewall rules,
     update the `/etc/iptables/rules.v4` file instead" [doc] https://docs.oracle.com/en-us/iaas/Content/Compute/known-issues.htm .
     Add, above the final `REJECT` rule of the `INPUT` chain (look at your file; its exact contents were not checked):

         -A INPUT -p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT
         -A INPUT -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT
         -A INPUT -p udp -m udp --dport 443 -j ACCEPT

     Apply without rebooting: `sudo iptables-restore < /etc/iptables/rules.v4` (the command given on the same page). Docker adds
     its own chains when it starts, so test from outside (section 9, step 7) instead of trusting the file.
   - **Oracle Linux** images (firewalld): add `--add-service=http --add-service=https --add-port=443/udp` with `--permanent`. Oracle warns
     that `firewall-cmd --reload` on a running instance can hang it by losing the boot volume's iSCSI connection; its workaround is
     to run the command twice, "using the `permanent` parameter the first time" and without it the second time [doc] known-issues.htm.
     Do not use `--reload`.

## 3. Create the A1 instance

1. Compute > Instances > Create instance. Image: **Canonical Ubuntu 24.04 (aarch64)** **[verify in the console that the image is
   offered for A1 in your region]**. Shape: `VM.Standard.A1.Flex` with the OCPU/memory of the option in section 0. Public subnet,
   the NSG above, "assign a public IPv4 address". Upload your SSH **public** key (never generate keys on the server).
2. **Boot volume.** "The minimum boot volume size for each instance is 47 GB, regardless of shape" (Compute section) while the Block
   Volume section says the default is 50 GB [doc, the page contradicts itself]; all boot and block volumes of the tenancy share
   200 GB of Always Free storage [doc]. 100 GB for production and 50 GB for staging leaves headroom for 30 days of dumps and Docker
   layers.
3. **"Out of host capacity".** The error means "a temporary lack of Always Free shapes in your home region" (Always Free page) and
   "a lack of physical infrastructure capacity for the shape in the requested fault domain and availability domain" (known-issues
   page). Oracle's workarounds [doc] known-issues.htm: ask for a capacity report with the `CreateComputeCapacityReport` operation,
   try a different availability domain, leave the fault domain unspecified, use a smaller shape, wait a few minutes and retry. A
   region with one availability domain (Johannesburg, Amsterdam, Paris, Zurich in the regions table) leaves you only the
   smaller-shape and wait options. Create it small (1 OCPU / 6 GB) and resize later if capacity is the problem **[verify that
   A1.Flex can be resized in place in the console]**. Upgrading to Pay As You Go is Oracle's own suggestion (section 1).
4. SSH in as `ubuntu` with your key and confirm: `uname -m` prints `aarch64`.

## 4. Prepare the VM

Run as `ubuntu` with sudo.

    sudo apt-get update && sudo apt-get -y upgrade
    sudo apt-get install -y unattended-upgrades rsync fail2ban iptables-persistent ca-certificates curl
    sudo dpkg-reconfigure -plow unattended-upgrades      # security updates on; reboot window is your decision

- **Docker Engine and Compose v2.24 or newer** (the overlay uses `!reset` and `!override`): install from Docker's apt repository
  for Ubuntu following https://docs.docker.com/engine/install/ubuntu/ (arm64 is a supported architecture there; not
  re-verified here), then `docker compose version`. Do not use the distribution's old `docker.io`/`docker-compose` packages.
- **Deploy user**, as in `deploy.md`: `sudo adduser --disabled-password --gecos "" deploy && sudo usermod -aG docker deploy`,
  put the deploy public key in `/home/deploy/.ssh/authorized_keys` (mode 600), `sudo install -d -o deploy -g deploy /opt/jobfinder`.
- **Swap.** A1 has no swap by default **[verify with `swapon --show`]**. A small swap file protects against a short memory
  spike killing Postgres; it is not a substitute for memory. Production (12-16 GB): 4 GB. Staging (8 GB): 4 GB.

      sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
      echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
      echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf && sudo sysctl --system

- **Time and logs.** `timedatectl` should say NTP is active; the compose stack rotates container logs (10 MB x 5).
- Pull access to GHCR with a read-only token, the env file, the backup passphrase file and preflight: `deploy.md` steps 5-7.

## 5. Images: arm64 is built and published by CI

The `deploy` workflow publishes one multi-architecture index per image tag (`linux/amd64` and `linux/arm64`), so `docker compose pull` on
the A1 VM selects the arm64 image by itself; nothing is built on the server.

- **CI build approach.** Each image is built per platform on a runner of that architecture (`ubuntu-latest` for amd64,
  `ubuntu-24.04-arm` for arm64), scanned with Trivy per platform, pushed by digest, and a `manifest` job joins the digests into
  the `sha-<commit>` (and release) tags and then checks that the published tag really lists both platforms. The repository is
  public, and GitHub offers its standard Linux arm64 runners (`ubuntu-24.04-arm`) free for public repositories; that was
  **not** verified against GitHub's documentation here (the page could not be fetched), so the first pull-request run of
  the workflow is the check **[verify]**. If the repository becomes private, or the label is unavailable, change the arm64 matrix
  entry to `runs-on: ubuntu-latest` and add a `docker/setup-qemu-action` step before the build; expect a much longer build. Why not QEMU: it works
  (`docker/setup-qemu-action`) and is the fallback for a private repository, but Maven, `npm run build` (Next.js SWC) and `uv sync`
  run several times slower under emulation, and the 45-minute job limit would be at risk for core-api and web. Native builds also
  mean the Trivy scan sees the real arm64 packages.
- **Third-party images.** `infra/deploy/check-multiarch.sh` (run in `deploy-checks` and weekly in `base-images`) fails when any image the
  stack uses is not a multi-arch index with amd64 and arm64. Audit result and the one re-pin are in ADR 0041, addendum 1.
- **Never** copy an amd64-only image to the VM: it fails with `exec format error`.

## 6. Production / staging split and the compose limits

Limits in `infra/docker-compose.prod.yml` (defaults; each is an environment variable): caddy 192m, postgres 1g, redis 256m,
rabbitmq 512m, ClamAV 2g, core-api 1536m, ai-service 1g, web 512m, backup 256m = **7.2 GiB** of limits (a test keeps this at or
below 8 GiB). The limits are ceilings, not reservations.

| Host | Memory | Fits? | What to do |
|---|---|---|---|
| Production, option A (2 OCPU / 12 GB) | 12 GB | yes: 7.2 GiB of limits leaves about 4.8 GiB for the OS, page cache and the 4 GB swap | defaults |
| Production, option B (3 OCPU / 16 GB) | 16 GB | yes, with room to raise Postgres (`POSTGRES_MEM_LIMIT=2g`) once the database grows | defaults |
| Staging (1 OCPU / 8 GB) | 8 GB | defaults leave only about 0.8 GiB: **no** | append `infra/deploy/env.staging-small.example` to the staging `.env` (5.75 GiB of limits, ClamAV kept at 1792m) |
| Staging at 6 GB | 6 GB | **no** with ClamAV (1.5 GB for signatures alone) | use 8 GB; running without the scanner needs `UPLOAD_SCANNER_TYPE=none` plus `ALLOW_NO_UPLOAD_SCAN=true` and removing the `clamav` service, which the overlay does not support without a local override |

CPU: the `*_CPUS` values are caps; on 2-3 OCPU the sum of caps (8.5) is not a problem because they are rarely all busy. core-api's JVM
takes 25% of its memory limit as heap by default, so do not lower `CORE_API_MEM_LIMIT` below 1g. Watch real usage with
`docker stats --no-stream` after the first day, and change limits only with evidence.

## 7. Offsite backups: Object Storage in the second tenancy

Why a different tenancy: the dumps (encrypted with the passphrase file) must survive the loss of the production account. Use
the second account **only** for this bucket (see the terms warning in section 1).

**Facts [doc]** https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi.htm :
- The S3 Compatibility API accepts "path-style or virtual-hosted style URLs". Path-style endpoint as documented on that page:
  `https://<namespace>.compat.objectstorage.<region>.oci.customer-oci.com` (bucket and key in the path). Virtual-hosted:
  `https://<bucket>.vhcompat.objectstorage.<region>.oci.customer-oci.com`. Older Oracle material uses `...oraclecloud.com`
  host names; copy the endpoint from the current page for your region **[verify in the console / on the page]**.
- Region: "Set the target region as one of the Oracle Cloud Infrastructure regions"; if the client cannot, use `us-east-1` or blank,
  which then works only in the home region. `BACKUP_S3_REGION` takes the real region identifier (for example `eu-frankfurt-1`).
- "AWS Signature Version 2 (SigV2) isn't supported" (the AWS CLI uses SigV4). Encryption at rest is on by default; there are no
  object ACLs; buckets created through the S3 API land in the root compartment unless a compartment is designated, so create
  the bucket in the console in the compartment you want.
- Supported object calls include PutObject, GetObject, DeleteObject, HeadObject, HeadBucket, multipart upload and `ListObjects`
  [doc] https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi_topic-Amazon_S3_Compatibility_API_Support.htm .
  That page does **not** mention `ListObjectsV2`, so `prune.sh` tries V2 and falls back to `ListObjects` (tested with a fake client).
  It does not document `Expect: 100-continue`; the backup image already removes that header.

**Create, in the second tenancy's console:**
1. A compartment `jobfinder-backup` and a **private** bucket `jobfinder-backups` in it. Note the Object Storage **namespace**
   (shown on the bucket page and the tenancy details) **[verify in the console]**.
2. An IAM group `backup-writers` and a local IAM user `backup-bot` in it. Policies (patterns from Oracle's common-policies page
   [doc] https://docs.oracle.com/en-us/iaas/Content/Identity/Concepts/commonpolicies.htm ):

       Allow group backup-writers to read buckets in compartment jobfinder-backup
       Allow group backup-writers to manage objects in compartment jobfinder-backup where target.bucket.name='jobfinder-backups'

   The backup job needs to list, upload, download (restore) and delete (prune) objects of that one bucket, which is what these two
   statements grant. Do not put the user in the Administrators group. Check the result by trying an upload to another bucket (it must fail).
3. A **Customer Secret Key** for `backup-bot` (user settings > Customer Secret Keys > Generate Secret Key). "Copy the Secret Key
   immediately, because you can't retrieve the Secret Key again"; each user can have up to two keys; they do not expire; the Access
   Key is shown next to the key's name [doc] https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/managingcredentials.htm .
   Store both in the password manager.
4. **Lifecycle rule** (backstop at 31 days, as `bucket-lifecycle.json` does for S3 stores): bucket > Lifecycle policy rules > Create
   rule: delete objects, 31 days, name filter prefix `backups/`. "The platform runs the lifecycle policy once a day" and "it can take
   up to 24 hours" to take effect; deleted objects cannot be recovered. It needs a policy in the root compartment of that tenancy:
   `Allow service objectstorage-<region_identifier> to manage object-family in compartment jobfinder-backup` [doc]
   https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/usinglifecyclepolicies.htm . `bucket-lifecycle.json` is an S3
   lifecycle document; whether the compatibility API accepts it was not documented, so use the console rule.
5. Free capacity: the Always Free page gives 20 GB of Object Storage per tenancy and 50,000 API requests per month when the tenancy is
   Always Free only (10 GB each of Standard, Infrequent Access and Archive on a paid account or trial) [doc]. Compressed
   dumps are small now; compute `30 x dump size` before the database grows.

**What goes in the production `.env`** (names from `env.production.example`; no value below is real):

    BACKUP_S3_URI=s3://jobfinder-backups/backups
    BACKUP_S3_ENDPOINT=https://<namespace>.compat.objectstorage.<region>.oci.customer-oci.com
    BACKUP_S3_REGION=<region identifier of the second tenancy, e.g. eu-frankfurt-1>
    BACKUP_S3_ADDRESSING_STYLE=path
    BACKUP_S3_ACCESS_KEY=<Access Key of the Customer Secret Key>
    BACKUP_S3_SECRET_KEY=<Secret Key of the Customer Secret Key>
    BACKUP_PASSPHRASE_HOST_FILE=/opt/jobfinder/backup-passphrase
    BACKUP_PASSPHRASE_FILE=/run/secrets/backup_passphrase

`BACKUP_S3_ADDRESSING_STYLE` is new (path, virtual or auto; empty keeps the client default); `preflight.sh` rejects other values.
Verify the first copy by hand: `docker compose exec backup /usr/local/bin/backup.sh manual`, then look at the bucket in the console, then
run the restore drill from `backup-restore.md` against it.

The application's own object storage (CV uploads, `OBJECT_STORAGE_*`) stays on Cloudflare R2 as in PLAN section 12. Using an OCI bucket there
is possible through the same S3 API (`OBJECT_STORAGE_PATH_STYLE=true`) but was **not** tested with the Java client; do not switch without a test.

## 8. DNS, TLS, and failure modes

- **DNS.** An A record (and AAAA only if the VNIC has an IPv6 address and the rules above allow it) for `SITE_ADDRESS` pointing at the VM's public
  IP, with a low TTL for the first deploy. Caddy obtains the certificate itself on its first start and needs ports 80 and 443 reachable from the
  internet; if the name does not resolve yet, Caddy retries with back-off, so create the record first. Set `ACME_EMAIL`.
- **TLS** is Caddy's automatic HTTPS (Let's Encrypt/ZeroSSL); `CADDY_TLS` stays empty in production (`preflight.sh` refuses the local CA there).
- **Idle reclaim.** [doc] "Idle Always Free compute instances may be reclaimed by Oracle." An instance is idle if during a 7-day period CPU
  utilization at the 95th percentile is under 20%, network utilization under 20% **and** memory utilization under 20% (memory applies to A1 only).
  A stack running Postgres, the JVM and ClamAV is far above 20% memory, so it should not qualify, but the page does not say a Pay As You Go tenancy
  is exempt, and a staging VM that is mostly idle on CPU and network might be close. Monitor it; keep backups offsite so a reclaimed VM costs a rebuild, not data.
- **Out of capacity** when creating or re-creating the VM (section 3). Keep the boot volume and the deploy state backed up so a replacement VM can be
  built from `deploy.md` in an hour; keep the offsite backups current. Oracle block volume backups: five are included in the free resources [doc].
- **Single VM, no high availability.** A reboot, an Oracle maintenance event or a bad deploy is downtime; deploys already restart the application
  for tens of seconds (ADR 0041). The restore objective is the last daily backup (up to 24 h of data) plus the time to rebuild.
- **Free Trial expiry and region limits.** Trial credits end after 30 days; Always Free resources continue. Only one subscribed region is allowed,
  so the backup tenancy's bucket is in that tenancy's own home region, not a second region of the production tenancy.
- **Cost surprises.** Anything above 2 OCPU / 12 GB of A1, above 200 GB of block and boot storage, or above 10 TB per month of outbound data is billed on a Pay As
  You Go tenancy [doc]. Set a budget alert.

## 9. First deploy, adapted to OCI

1. Account, region, Pay As You Go decision, budget alert (sections 0-1). Second tenancy and bucket (section 7).
2. VCN, NSG, reserved IP, instance, host firewall (sections 2-3). From your machine: `ssh ubuntu@<ip> uname -m` prints `aarch64`.
3. Prepare the VM (section 4); log in to GHCR as the deploy user; copy `env.production.example` to `/opt/jobfinder/.env` and fill it; on staging append
   `env.staging-small.example`. Create the passphrase file.
4. DNS record for `SITE_ADDRESS`; wait for it to resolve (`dig +short <name>`).
5. `infra/deploy/preflight.sh /opt/jobfinder/.env` until there are no errors.
6. Staging first: merge to `main` (or run the manual `deploy` workflow for staging with a `sha-` tag). Confirm the pulled images are arm64:
   `docker image inspect --format '{{.Architecture}}' ghcr.io/<owner>/jobfinder/core-api:<tag>` prints `arm64`.
7. From outside the VM (a different network): `curl -sI http://<name>` redirects to HTTPS, `curl -sI https://<name>` answers, `nc -vz <ip> 5432`
   (and 6379, 5672, 8080) is refused or times out. Run `SMOKE_BASE_URL=https://<name> infra/deploy/smoke.sh`.
8. Backup and restore drill against the OCI bucket (section 7 and `backup-restore.md`). Look at the object in the console; check the lifecycle rule exists.
9. Tag `vX.Y.Z`, approve the production deploy, smoke test again, confirm the first scheduled backup appears and `backup` is healthy.

## What was tested (and what was not)

Tested locally on an arm64 Mac (Docker, native arm64): all four images build for `linux/arm64` without cache; the production overlay ran with them in an
isolated rehearsal project (29 smoke checks, backup round trip with `BACKUP_S3_ADDRESSING_STYLE=path` against the S3 mock); the multi-arch check script
ran against the live registries and against fixtures; the ClamAV Debian image was started on arm64 with a 2 GiB limit (ClamAV 1.4.6, `clamdcheck.sh` "Clamd is up", `PONG` on 3310, about 980 MiB resident). Not tested: any
OCI resource, the OCI S3 endpoint, the GitHub arm64 runners and the manifest job (they run for the first time on the pull request), Ubuntu 24.04 aarch64 on OCI,
Docker installation on that image, the host firewall rules, and ClamAV memory on the real A1 VM.
