-- Replay reports were saved without the account that owns the run, so the
-- per-account drift history had nothing to show. New reports carry it; this
-- fills it in for the ones already recorded, from the run they belong to.
update replay_verification_reports r
   set developer_id = w.developer_id
  from workflow_instances w
 where r.developer_id is null
   and r.workflow_id = w.workflow_id;
