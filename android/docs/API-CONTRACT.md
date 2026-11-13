# 已适配的 API 契约速查

> 来源：`dev` 分支 `src/lib/api.ts` 的 `ROUTES` 表（Tauri command → HTTP 路由），
> 服务端实现位于 `crates/buddy-switch-server/src/api.rs`（axum）。
> 图例：**✅** 手机端已调用 · **—** 契约已识别，暂未接入 UI。

共识别 **284** 条路由，其中 **31** 条已在手机端接入。

| 方法 | 路径 | Tauri command | 手机端 |
|:--|:--|:--|:--:|
| | **状态 / 连接（1）** | | |
| `GET` | `/api/status` | `get_status` | ✅ |
| | **账号（WorkBuddy）（25）** | | |
| `POST` | `/api/account/group` | `set_account_group` | — |
| `POST` | `/api/account/remark` | `set_account_remark` | — |
| `GET` | `/api/account/token-today` | `get_account_token_today` | — |
| `GET` | `/api/account/token-trend` | `get_gateway_token_trend` | — |
| `GET` | `/api/account/usage-dim` | `get_gateway_usage_dim` | — |
| `GET` | `/api/account/usage-reconcile` | `get_usage_reconcile` | — |
| `GET` | `/api/accounts` | `get_accounts` | ✅ |
| `POST` | `/api/accounts/delete` | `account_delete` | ✅ |
| `POST` | `/api/accounts/export` | `accounts_export` | — |
| `POST` | `/api/accounts/export-to-path` | `accounts_export_to_path` | — |
| `POST` | `/api/accounts/import` | `accounts_import` | — |
| `POST` | `/api/accounts/import-local` | `accounts_import_local` | — |
| `GET` | `/api/accounts/list` | `accounts_list` | ✅ |
| `POST` | `/api/accounts/open-dir` | `open_accounts_dir` | — |
| `POST` | `/api/accounts/paste-credential` | `accounts_paste_credential` | ✅ |
| `POST` | `/api/accounts/remark` | `account_remark` | ✅ |
| `GET` | `/api/accounts/trash` | `account_trash_list` | ✅ |
| `POST` | `/api/accounts/trash/clear` | `account_trash_clear` | — |
| `POST` | `/api/accounts/trash/purge` | `account_purge` | — |
| `POST` | `/api/accounts/trash/restore` | `account_restore` | ✅ |
| `POST` | `/api/delete` | `delete_account` | — |
| `POST` | `/api/switch` | `switch_account` | ✅ |
| `GET` | `/api/switch/config` | `get_switch_config` | — |
| `POST` | `/api/switch/config` | `save_switch_config` | — |
| `GET` | `/api/switch/progress` | `switch_progress` | — |
| | **积分（3）** | | |
| `POST` | `/api/credits` | `get_credit_expiry` | ✅ |
| `POST` | `/api/credits/query` | `credits` | ✅ |
| `GET` | `/api/credits/stats` | `get_credit_statistics` | ✅ |
| | **签到（7）** | | |
| `POST` | `/api/checkin` | `checkin` | ✅ |
| `POST` | `/api/checkin/all` | `checkin_all` | ✅ |
| `GET` | `/api/checkin/config` | `get_auto_checkin_config` | ✅ |
| `POST` | `/api/checkin/config` | `save_auto_checkin_config` | ✅ |
| `GET` | `/api/checkin/logs` | `get_checkin_logs` | ✅ |
| `GET` | `/api/checkin/status` | `get_checkin_status` | ✅ |
| `POST` | `/api/checkin/status/query` | `checkin_status` | ✅ |
| | **登录 / OAuth（4）** | | |
| `POST` | `/api/auth/webview/close` | `auth_webview_close` | — |
| `POST` | `/api/auth/webview/open` | `auth_webview_open` | — |
| `POST` | `/api/oauth/start` | `oauth_start` | ✅ |
| `POST` | `/api/oauth/status` | `oauth_status` | ✅ |
| | **Trae（63）** | | |
| `GET` | `/api/trae/account/token-today` | `get_trae_account_token_today` | — |
| `GET` | `/api/trae/accounts` | `get_trae_accounts` | ✅ |
| `POST` | `/api/trae/accounts/add` | `trae_add_account` | — |
| `POST` | `/api/trae/accounts/delete` | `trae_delete_account` | — |
| `POST` | `/api/trae/accounts/export` | `trae_export_accounts` | — |
| `POST` | `/api/trae/accounts/export-to-path` | `trae_export_accounts_to_path` | — |
| `POST` | `/api/trae/accounts/import` | `trae_import_accounts` | — |
| `POST` | `/api/trae/accounts/import-local` | `trae_import_local_account` | — |
| `POST` | `/api/trae/accounts/import/preview` | `trae_preview_import_accounts` | — |
| `POST` | `/api/trae/accounts/remark` | `trae_set_account_remark` | — |
| `POST` | `/api/trae/accounts/update` | `trae_update_account` | — |
| `GET` | `/api/trae/capabilities` | `get_trae_capabilities` | — |
| `POST` | `/api/trae/checkin` | `trae_checkin` | ✅ |
| `GET` | `/api/trae/checkin/status` | `get_trae_checkin_status` | — |
| `GET` | `/api/trae/checkin/status-map` | `get_trae_checkin_status_map` | — |
| `GET` | `/api/trae/client-import/config` | `get_trae_client_db_config` | — |
| `POST` | `/api/trae/client-import/path` | `save_trae_client_db_path` | — |
| `GET` | `/api/trae/client-import/probe` | `probe_trae_client_db` | — |
| `POST` | `/api/trae/client-import/unlock` | `unlock_trae_client_db` | — |
| `GET` | `/api/trae/client-usage` | `get_trae_client_token_stats` | — |
| `POST` | `/api/trae/cooldown/clear` | `trae_clear_cooldown` | — |
| `GET` | `/api/trae/credits` | `get_trae_credits` | ✅ |
| `POST` | `/api/trae/credits/refresh` | `trae_refresh_credits` | — |
| `POST` | `/api/trae/device/reset` | `trae_reset_device` | — |
| `GET` | `/api/trae/env` | `get_trae_env` | — |
| `GET` | `/api/trae/gateway/client-models` | `get_trae_client_models` | — |
| `POST` | `/api/trae/gateway/client-models/refresh` | `refresh_trae_client_models` | — |
| `GET` | `/api/trae/gateway/config` | `get_trae_gateway_config` | — |
| `POST` | `/api/trae/gateway/config` | `save_trae_gateway_config` | — |
| `GET` | `/api/trae/gateway/keys` | `list_trae_api_keys` | — |
| `POST` | `/api/trae/gateway/keys` | `create_trae_api_key` | — |
| `POST` | `/api/trae/gateway/keys/delete` | `delete_trae_api_key` | — |
| `POST` | `/api/trae/gateway/keys/plaintext` | `save_trae_api_key_plaintext` | — |
| `POST` | `/api/trae/gateway/keys/policy` | `set_trae_key_policy` | — |
| `POST` | `/api/trae/gateway/keys/restore` | `restore_trae_api_key` | — |
| `POST` | `/api/trae/gateway/keys/revoke` | `revoke_trae_api_key` | — |
| `GET` | `/api/trae/gateway/keys/usage` | `trae_key_usage` | — |
| `GET` | `/api/trae/gateway/logs` | `get_trae_gateway_logs` | — |
| `POST` | `/api/trae/gateway/logs/clear` | `clear_trae_gateway_logs` | — |
| `GET` | `/api/trae/gateway/models` | `get_trae_gateway_models` | — |
| `POST` | `/api/trae/gateway/pool/exclusions` | `set_trae_gateway_pool_exclusion` | — |
| `GET` | `/api/trae/gateway/status` | `trae_gateway_status` | — |
| `GET` | `/api/trae/gateway/usage-dim` | `get_trae_gateway_usage_dim` | — |
| `POST` | `/api/trae/groups` | `trae_group_op` | — |
| `POST` | `/api/trae/launch-client` | `trae_launch_client` | — |
| `POST` | `/api/trae/legacy-merge` | `trae_merge_legacy_regions` | — |
| `POST` | `/api/trae/login/save` | `trae_save_login` | — |
| `GET` | `/api/trae/logs` | `get_trae_logs` | — |
| `POST` | `/api/trae/oauth/cancel` | `trae_oauth_cancel` | — |
| `POST` | `/api/trae/oauth/start` | `trae_oauth_start` | ✅ |
| `POST` | `/api/trae/oauth/status` | `trae_oauth_status` | ✅ |
| `POST` | `/api/trae/oauth/submit-callback` | `trae_oauth_submit_callback` | — |
| `POST` | `/api/trae/open-data-dir` | `open_trae_data_dir` | — |
| `GET` | `/api/trae/profiles` | `get_trae_profiles` | — |
| `POST` | `/api/trae/profiles/backup` | `trae_backup_profile` | — |
| `POST` | `/api/trae/profiles/delete` | `trae_delete_profile` | — |
| `POST` | `/api/trae/profiles/restore` | `trae_restore_profile` | — |
| `POST` | `/api/trae/refresh-jwt` | `trae_refresh_jwt` | — |
| `GET` | `/api/trae/settings` | `get_trae_settings` | — |
| `POST` | `/api/trae/settings` | `save_trae_settings` | — |
| `POST` | `/api/trae/switch` | `trae_switch_account` | — |
| `GET` | `/api/trae/token-stats` | `get_trae_token_statistics` | — |
| `GET` | `/api/trae/variants` | `get_trae_variants` | — |
| | **Qoder（46）** | | |
| `GET` | `/api/qoder/accounts` | `get_qoder_accounts` | ✅ |
| `POST` | `/api/qoder/accounts/delete` | `qoder_delete_account` | — |
| `POST` | `/api/qoder/accounts/group` | `qoder_set_account_group` | — |
| `POST` | `/api/qoder/accounts/import-local` | `qoder_import_local` | — |
| `POST` | `/api/qoder/accounts/import-pat` | `qoder_import_pat` | — |
| `POST` | `/api/qoder/accounts/refresh` | `qoder_refresh_account` | — |
| `POST` | `/api/qoder/accounts/remark` | `qoder_set_account_remark` | — |
| `POST` | `/api/qoder/accounts/switch` | `qoder_switch_account` | — |
| `POST` | `/api/qoder/checkin/claim` | `qoder_checkin_claim` | ✅ |
| `GET` | `/api/qoder/checkin/logs` | `qoder_checkin_logs` | — |
| `GET` | `/api/qoder/checkin/status` | `qoder_checkin_status` | — |
| `GET` | `/api/qoder/checkin/status-map` | `qoder_checkin_status_map` | — |
| `GET` | `/api/qoder/credits` | `qoder_credits` | ✅ |
| `GET` | `/api/qoder/gateway/config` | `get_qoder_gateway_config` | — |
| `POST` | `/api/qoder/gateway/config` | `save_qoder_gateway_config` | — |
| `GET` | `/api/qoder/gateway/keys` | `list_qoder_api_keys` | — |
| `POST` | `/api/qoder/gateway/keys` | `create_qoder_api_key` | — |
| `POST` | `/api/qoder/gateway/keys/delete` | `delete_qoder_api_key` | — |
| `POST` | `/api/qoder/gateway/keys/plaintext` | `save_qoder_api_key_plaintext` | — |
| `POST` | `/api/qoder/gateway/keys/policy` | `set_qoder_key_policy` | — |
| `POST` | `/api/qoder/gateway/keys/restore` | `restore_qoder_api_key` | — |
| `POST` | `/api/qoder/gateway/keys/revoke` | `revoke_qoder_api_key` | — |
| `GET` | `/api/qoder/gateway/keys/usage` | `qoder_key_usage` | — |
| `GET` | `/api/qoder/gateway/logs` | `get_qoder_gateway_logs` | — |
| `POST` | `/api/qoder/gateway/logs/clear` | `clear_qoder_gateway_logs` | — |
| `GET` | `/api/qoder/gateway/models` | `get_qoder_gateway_models` | — |
| `POST` | `/api/qoder/gateway/pool/exclusions` | `set_qoder_gateway_pool_exclusion` | — |
| `GET` | `/api/qoder/gateway/quota` | `qoder_gateway_quota` | — |
| `GET` | `/api/qoder/gateway/status` | `qoder_gateway_status` | — |
| `GET` | `/api/qoder/gateway/usage-dim` | `get_qoder_gateway_usage_dim` | — |
| `GET` | `/api/qoder/local-sources` | `get_qoder_local_sources` | — |
| `POST` | `/api/qoder/oauth/start` | `qoder_oauth_start` | ✅ |
| `POST` | `/api/qoder/oauth/status` | `qoder_oauth_status` | ✅ |
| `POST` | `/api/qoder/pro-pack/claim` | `qoder_pro_pack_claim` | — |
| `GET` | `/api/qoder/pro-pack/status` | `qoder_pro_pack_status` | — |
| `GET` | `/api/qoder/programs` | `qoder_program_status` | — |
| `GET` | `/api/qoder/usage` | `qoder_usage` | — |
| `GET` | `/api/qoder/web/credit-packs` | `qoder_web_credit_packs` | — |
| `POST` | `/api/qoder/web/credit-packs/probe` | `qoder_web_credit_packs_probe` | — |
| `POST` | `/api/qoder/web/login-close` | `qoder_web_login_close` | — |
| `POST` | `/api/qoder/web/login-open` | `qoder_web_login_open` | — |
| `GET` | `/api/qoder/web/proxy` | `qoder_web_proxy_get` | — |
| `POST` | `/api/qoder/web/proxy` | `qoder_web_proxy_set` | — |
| `POST` | `/api/qoder/web/session/logout` | `qoder_web_session_logout` | — |
| `GET` | `/api/qoder/web/session/status` | `qoder_web_session_status` | — |
| `GET` | `/api/qoder/web/session/window-cookies` | `qoder_web_window_cookies` | — |
| | **网关 / Key / 模型（50）** | | |
| `GET` | `/api/gateway/config` | `get_gateway_config` | — |
| `POST` | `/api/gateway/config` | `save_gateway_config` | — |
| `GET` | `/api/gateway/disabled-models` | `get_disabled_models` | — |
| `POST` | `/api/gateway/disabled-models` | `set_model_disabled` | — |
| `GET` | `/api/gateway/fallback-stats` | `get_fallback_stats` | — |
| `GET` | `/api/gateway/keys` | `list_api_keys` | — |
| `POST` | `/api/gateway/keys` | `create_api_key` | — |
| `POST` | `/api/gateway/keys/delete` | `delete_api_key` | — |
| `POST` | `/api/gateway/keys/plaintext` | `save_api_key_plaintext` | — |
| `POST` | `/api/gateway/keys/policy` | `set_key_policy` | — |
| `POST` | `/api/gateway/keys/restore` | `restore_api_key` | — |
| `POST` | `/api/gateway/keys/revoke` | `revoke_api_key` | — |
| `GET` | `/api/gateway/keys/usage` | `key_usage` | — |
| `GET` | `/api/gateway/logs` | `get_gateway_logs` | — |
| `POST` | `/api/gateway/logs/clear` | `clear_gateway_logs` | — |
| `GET` | `/api/gateway/model-fallback` | `get_model_fallback` | — |
| `POST` | `/api/gateway/model-fallback` | `save_model_fallback` | — |
| `POST` | `/api/gateway/model-fallback/preview` | `preview_model_fallback` | — |
| `GET` | `/api/gateway/model-image-caps` | `get_model_image_caps` | — |
| `POST` | `/api/gateway/model-image-caps` | `set_model_image_cap` | — |
| `GET` | `/api/gateway/models` | `get_gateway_models` | — |
| `POST` | `/api/gateway/models/refresh` | `refresh_gateway_models` | — |
| `POST` | `/api/gateway/open-data-dir` | `open_workbuddy_data_dir` | — |
| `GET` | `/api/gateway/pool/exclusions` | `gateway_pool_exclusions` | — |
| `POST` | `/api/gateway/pool/exclusions` | `set_gateway_pool_exclusion` | — |
| `POST` | `/api/gateway/pool/revive` | `revive_gateway_account` | — |
| `POST` | `/api/gateway/probe/cancel` | `probe_cancel` | — |
| `GET` | `/api/gateway/probe/ledger` | `probe_ledger` | — |
| `POST` | `/api/gateway/probe/start` | `probe_start` | — |
| `GET` | `/api/gateway/probe/status` | `probe_status` | — |
| `POST` | `/api/gateway/risk/recover` | `recover_gateway_risk_account` | — |
| `GET` | `/api/gateway/status` | `gateway_status` | — |
| `GET` | `/api/gateway/strategy` | `get_account_strategy` | — |
| `POST` | `/api/gateway/strategy` | `save_account_strategy` | — |
| `POST` | `/api/proxy/disable` | `proxy_disable` | — |
| `POST` | `/api/proxy/enable` | `proxy_enable` | — |
| `GET` | `/api/proxy/status` | `proxy_status` | — |
| `GET` | `/api/unified-gateway/config` | `get_unified_gateway_config` | — |
| `POST` | `/api/unified-gateway/config` | `save_unified_gateway_config` | — |
| `GET` | `/api/unified-gateway/keys` | `list_unified_api_keys` | — |
| `POST` | `/api/unified-gateway/keys` | `create_unified_api_key` | — |
| `POST` | `/api/unified-gateway/keys/delete` | `delete_unified_api_key` | — |
| `POST` | `/api/unified-gateway/keys/plaintext` | `save_unified_api_key_plaintext` | — |
| `POST` | `/api/unified-gateway/keys/policy` | `set_unified_key_policy` | — |
| `POST` | `/api/unified-gateway/keys/restore` | `restore_unified_api_key` | — |
| `POST` | `/api/unified-gateway/keys/revoke` | `revoke_unified_api_key` | — |
| `GET` | `/api/unified-gateway/keys/usage` | `unified_key_usage` | — |
| `GET` | `/api/unified-gateway/keys/usage-all` | `unified_key_usage_all` | — |
| `GET` | `/api/unified-gateway/models` | `get_unified_gateway_models` | — |
| `GET` | `/api/unified-gateway/pool-overview` | `unified_gateway_pool_overview` | — |
| | **用量 / 统计（6）** | | |
| `GET` | `/api/rotate/config` | `get_auto_rotate_config` | — |
| `POST` | `/api/rotate/config` | `save_auto_rotate_config` | — |
| `GET` | `/api/rotate/logs` | `get_rotate_logs` | — |
| `POST` | `/api/rotate/run` | `run_rotate` | — |
| `GET` | `/api/rotate/status` | `rotate_status` | — |
| `GET` | `/api/token-stats` | `get_token_statistics` | — |
| | **备份 / 导入导出（12）** | | |
| `GET` | `/api/backup/auto/config` | `get_auto_backup_config` | — |
| `POST` | `/api/backup/auto/config` | `save_auto_backup_config` | — |
| `GET` | `/api/backup/auto/list` | `list_auto_backups` | — |
| `POST` | `/api/backup/auto/restore` | `restore_auto_backup` | — |
| `POST` | `/api/backup/export` | `export_backup` | — |
| `POST` | `/api/backup/import` | `import_backup` | — |
| `POST` | `/api/backup/import/preview` | `preview_import_backup` | — |
| `POST` | `/api/export-accounts` | `export_accounts` | — |
| `POST` | `/api/export-accounts-to-path` | `export_accounts_to_path` | — |
| `POST` | `/api/import` | `import_accounts` | — |
| `POST` | `/api/import-local` | `import_local` | — |
| `POST` | `/api/import/preview` | `preview_import_accounts` | — |
| | **客户端 / 编辑器集成（25）** | | |
| `POST` | `/api/agent-hooks/enabled` | `set_agent_hook_enabled` | — |
| `GET` | `/api/agent-hooks/status` | `get_agent_hook_status` | — |
| `POST` | `/api/codebuddy-cli/disable-gateway` | `codebuddy_cli_disable_gateway` | — |
| `POST` | `/api/codebuddy-cli/enable-gateway` | `codebuddy_cli_enable_gateway` | — |
| `POST` | `/api/codebuddy-cli/install-helper` | `install_codebuddy_cli_helper` | — |
| `GET` | `/api/codebuddy-cli/status` | `get_codebuddy_cli_status` | — |
| `POST` | `/api/codebuddy-cli/switch` | `switch_codebuddy_cli_account` | — |
| `POST` | `/api/codebuddy-cn-ide/detect` | `detect_codebuddy_cn_ide_account` | — |
| `GET` | `/api/codebuddy-cn-ide/status` | `get_codebuddy_cn_ide_status` | — |
| `POST` | `/api/codebuddy-cn-ide/switch` | `switch_codebuddy_cn_ide_account` | — |
| `POST` | `/api/codebuddy-ide/memory/merge` | `migrate_codebuddy_ide_memory` | — |
| `POST` | `/api/companion/jump` | `companion_jump` | — |
| `GET` | `/api/companion/status` | `companion_status` | — |
| `POST` | `/api/companion/toggle` | `companion_toggle` | — |
| `POST` | `/api/companion/visibility` | `companion_set_visible` | — |
| `POST` | `/api/jetbrains-codebuddy-plugin/detect` | `detect_jetbrains_codebuddy_plugin_account` | — |
| `GET` | `/api/jetbrains-codebuddy-plugin/status` | `get_jetbrains_codebuddy_plugin_status` | — |
| `POST` | `/api/jetbrains-codebuddy-plugin/switch` | `switch_jetbrains_codebuddy_plugin_account` | — |
| `POST` | `/api/vscode-codebuddy-plugin/detect` | `detect_vscode_codebuddy_plugin_account` | — |
| `GET` | `/api/vscode-codebuddy-plugin/status` | `get_vscode_codebuddy_plugin_status` | — |
| `POST` | `/api/vscode-codebuddy-plugin/switch` | `switch_vscode_codebuddy_plugin_account` | — |
| `GET` | `/api/vscode-sessions` | `list_vscode_sessions` | — |
| `POST` | `/api/vscode-sessions/copy` | `copy_vscode_sessions` | — |
| `GET` | `/api/vscode-sessions/diff` | `diff_vscode_sessions` | — |
| `POST` | `/api/vscode-sessions/sync` | `sync_vscode_sessions` | — |
| | **其它（42）** | | |
| `POST` | `/api/cache/clear` | `cache_clear` | — |
| `GET` | `/api/cache/scan` | `cache_scan` | — |
| `GET` | `/api/client-settings/backups` | `list_client_settings_backups` | — |
| `POST` | `/api/client-settings/diff` | `diff_client_settings` | — |
| `POST` | `/api/client-settings/patch` | `patch_client_settings` | — |
| `GET` | `/api/client-settings/read` | `read_client_settings` | — |
| `POST` | `/api/client-settings/restore` | `restore_client_settings_backup` | — |
| `GET` | `/api/client-settings/slots` | `list_client_settings_slots` | — |
| `GET` | `/api/client/limits` | `client_limits` | — |
| `POST` | `/api/data-dir/open` | `open_data_dir` | — |
| `POST` | `/api/diagnostics/export` | `export_diagnostic` | — |
| `POST` | `/api/logs/open-dir` | `open_runtime_logs_dir` | — |
| `GET` | `/api/logs/runtime` | `runtime_logs` | — |
| `POST` | `/api/logs/runtime/clear` | `clear_runtime_logs` | — |
| `POST` | `/api/logs/runtime/slow-call` | `report_slow_call` | — |
| `POST` | `/api/migrate/account` | `migrate_account_data` | — |
| `POST` | `/api/refresh-token` | `refresh_account_token` | — |
| `GET` | `/api/schedule/config` | `get_schedule_config` | — |
| `POST` | `/api/schedule/config` | `save_schedule_config` | — |
| `POST` | `/api/schedule/run` | `run_schedule_task` | — |
| `GET` | `/api/sessions` | `list_sessions` | — |
| `POST` | `/api/sessions/copy` | `copy_sessions` | — |
| `POST` | `/api/sessions/export` | `export_sessions_bundle` | — |
| `POST` | `/api/sessions/import` | `import_sessions_bundle` | — |
| `GET` | `/api/sessions/import/preview` | `preview_sessions_bundle` | — |
| `GET` | `/api/sessions/replicas` | `list_session_replicas` | — |
| `POST` | `/api/sessions/replicas/prune` | `prune_session_replicas` | — |
| `POST` | `/api/sessions/sync` | `sync_session_replicas` | — |
| `POST` | `/api/sessions/unify` | `unify_session_replicas` | — |
| `GET` | `/api/settings/risk-controls` | `get_risk_control_settings` | — |
| `POST` | `/api/settings/risk-controls` | `save_risk_control_settings` | — |
| `GET` | `/api/skills` | `list_skills` | — |
| `POST` | `/api/skills/copy-cross` | `copy_skills_cross` | — |
| `POST` | `/api/skills/enabled` | `set_skill_enabled` | — |
| `POST` | `/api/skills/import` | `import_skill` | — |
| `POST` | `/api/skills/open-dir` | `open_skills_dir` | — |
| `GET` | `/api/travel/config` | `get_auto_travel_config` | — |
| `POST` | `/api/travel/config` | `save_auto_travel_config` | — |
| `GET` | `/api/travel/status` | `get_travel_status` | — |
| `GET` | `/api/update/check` | `check_update` | — |
| `GET` | `/api/update/config` | `get_github_config` | — |
| `POST` | `/api/update/config` | `save_github_config` | — |

## 关键约定

- **Scope**：`wb-cn` / `wb-global` / `trae-cn` / `trae-global` / `qoder-cn` / `qoder-global`（`src/lib/scope.ts` 的 `SCOPES`）。
- **Region**：`cn` / `global`；统计查询可用 `all`。
- **GET** 参数走 query string；**POST** 参数走 JSON body（camelCase）。
- 服务端 `api_guard.rs`：Host 必须回环或在 `BUDDY_SWITCH_ALLOWED_HOSTS` 白名单；
  禁止 `Origin` 跨源 / `Sec-Fetch-Site: cross-site`；非 GET 请求必须 `Content-Type: application/json`。
- 默认端口：`57890`（界面 + API）；网关 `57891` WorkBuddy / `57892` 统一 / `57894` Trae / `57895` Qoder。
