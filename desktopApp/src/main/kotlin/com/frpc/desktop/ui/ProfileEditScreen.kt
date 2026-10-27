package com.frpc.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frpc.desktop.AppContainer
import com.frpc.shared.net.PortCheck
import com.frpc.desktop.data.ServerProfile
import com.frpc.desktop.engine.FrpcSupervisor
import com.frpc.shared.model.AuthConfig
import com.frpc.shared.model.FrpcConfig
import com.frpc.shared.model.LogConfig
import com.frpc.shared.model.ProxyConfig
import com.frpc.shared.model.TransportConfig
import com.frpc.shared.model.VisitorConfig
import com.frpc.shared.model.WebServerConfig
import com.frpc.shared.validate.ConfigValidator
import com.frpc.shared.validate.Reason
import com.frpc.shared.validate.Scope
import com.frpc.shared.validate.Severity
import com.frpc.shared.validate.ValidationIssue
import kotlinx.coroutines.launch
import java.awt.FileDialog

@Composable
fun ProfileEditScreen(
    container: AppContainer,
    initial: ServerProfile?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(initial?.id) {
        mutableStateOf(initial ?: ServerProfile(name = "新配置", config = FrpcConfig(auth = AuthConfig())))
    }
    var proxyEditing by remember { mutableStateOf<Int?>(null) }
    var proxyCreating by remember { mutableStateOf(false) }
    var visitorEditing by remember { mutableStateOf<Int?>(null) }
    var showErrors by remember { mutableStateOf(false) }
    // 端口输入的中间态：清空/逐字输入时不能立刻跳回默认值，保存时再解析
    var serverPortText by remember(initial?.id) { mutableStateOf((initial?.config?.serverPort ?: 7000).toString()) }
    var adminPortText by remember(initial?.id) {
        mutableStateOf((initial?.config?.webServer?.port ?: 7400).toString())
    }
    // 与其他配置重名时的提示（P5）
    var saveError by remember { mutableStateOf<String?>(null) }
    // 传输/TLS 的高级项默认收起：字段多且大部分用户不需要改
    var showTransportAdvanced by remember { mutableStateOf(false) }
    var showTlsAdvanced by remember { mutableStateOf(false) }
    // 代理规则超过 5 条时出现的本地筛选词
    var proxyQuery by remember { mutableStateOf("") }

    val issues = remember(draft.config) { ConfigValidator.validate(draft.config) }
    val errors = issues.filter { it.severity == Severity.ERROR }
    val warnings = issues.filter { it.severity == Severity.WARNING }

    // 运行中的配置允许编辑保存（旧做法是整页禁用，改一个端口都要先停服务），
    // 但必须让人看见「保存 ≠ 生效」，并在保存时给出是否立即重载的选择
    val runState by container.supervisor.state.collectAsState()
    val runningThis = initial != null &&
        runState.phase == FrpcSupervisor.Phase.RUNNING && runState.profileId == initial.id
    var confirmSaveRunning by remember { mutableStateOf(false) }

    fun updateConfig(block: (FrpcConfig) -> FrpcConfig) {
        draft = draft.copy(config = block(draft.config))
    }

    /**
     * 落盘 + 收尾。运行中的配置：
     * - reload = true：保存后立刻重载，改动马上生效
     * - reload = false：只存盘，打上「待重载」标记，由主页提示还有一笔改动没生效
     */
    fun save(reload: Boolean) {
        scope.launch {
            val saved = draft.copy(name = draft.name.ifBlank { "未命名配置" })
            container.profiles.upsert(saved)
            if (container.settings.state.value.defaultProfileId == null) {
                container.settings.setDefaultProfileId(saved.id)
            }
            if (runningThis) {
                if (reload) {
                    container.supervisor.clearPendingReload()
                    container.supervisor.reload()
                } else {
                    container.supervisor.markPendingReload(saved.id)
                }
            }
            onClose()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    if (initial == null) "新建配置" else "编辑配置",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose) { Text("取消") }
                Button(onClick = {
                    // 先做校验，运行中的配置再问要不要立刻重载（别校验都没过就弹窗）
                    updateConfig { it.copy(serverPort = serverPortText.toIntOrNull() ?: 0) }
                    draft.config.webServer?.let { ws ->
                        updateConfig { it.copy(webServer = ws.copy(port = adminPortText.toIntOrNull() ?: 0)) }
                    }
                    saveError = null
                    val currentIssues = ConfigValidator.validate(draft.config, portInUse = PortCheck::isInUse)
                    if (currentIssues.any { it.severity == Severity.ERROR }) {
                        showErrors = true
                    } else if (container.profiles.profiles.value.any {
                            it.name == draft.name.trim() && it.id != draft.id
                        }
                    ) {
                        saveError = "已存在同名配置「${draft.name.trim()}」，请换一个名称"
                        showErrors = false
                    } else if (runningThis) {
                        confirmSaveRunning = true
                    } else {
                        save(reload = false)
                    }
                }) { Text("保存") }
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (saveError != null) {
                SectionCard(title = "无法保存") {
                    Text(
                        text = saveError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 运行中编辑：不拦着改，但把「保存 ≠ 生效」写在最上面
            if (runningThis) {
                SectionCard(title = "该配置正在运行") {
                    Text(
                        text = "可以直接改并保存，但 frpc 仍在使用旧配置，需要「重载配置」（或停止后重新启动）才会生效。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showErrors && errors.isNotEmpty()) {
                SectionCard(title = "请先修正以下问题") {
                    errors.forEach {
                        Text(
                            text = "• " + validationText(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            // 警告不阻止保存，但必须可见（例如 xtcp 未填 STUN：能启动，但打洞大概率超时）
            if (warnings.isNotEmpty()) {
                SectionCard(title = "建议改进") {
                    warnings.forEach {
                        Text(
                            text = "• " + validationText(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard(title = "服务器") {
                LabeledTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = "配置名称",
                    supportingText = "只在本机区分多份配置，不会写进 frpc.toml",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledTextField(
                        value = draft.config.serverAddr,
                        onValueChange = { v -> updateConfig { it.copy(serverAddr = v) } },
                        label = "服务器地址",
                        placeholder = "frps.example.com",
                        supportingText = "frps 的地址或域名，不要带 http:// 前缀",
                        isError = showErrors && draft.config.serverAddr.isBlank(),
                        modifier = Modifier.weight(2f),
                    )
                    NumberField(
                        value = serverPortText,
                        // 只更新文本；解析成合法端口才提交，清空时保持输入而不是跳回 7000
                        onValueChange = { v ->
                            serverPortText = v
                            v.toIntOrNull()?.let { p -> updateConfig { it.copy(serverPort = p) } }
                        },
                        label = "服务器端口",
                        modifier = Modifier.weight(1f),
                        supportingText = "tcp/websocket 填 bind_port；kcp/quic 填 kcp_bind_port / quic_bind_port",
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledTextField(
                        value = draft.config.user.orEmpty(),
                        onValueChange = { v -> updateConfig { it.copy(user = v.trim().ifBlank { null }) } },
                        label = "代理名前缀（user）",
                        placeholder = "留空 = 不加前缀",
                        supportingText = "代理在 frps 上的最终名 =「前缀.代理名」",
                        modifier = Modifier.weight(1f),
                    )
                    LabeledTextField(
                        value = draft.config.clientID.orEmpty(),
                        onValueChange = { v -> updateConfig { it.copy(clientID = v.trim().ifBlank { null }) } },
                        label = "客户端标识（clientID）",
                        supportingText = "frps 面板用来区分设备；留空由 frpc 生成",
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow(
                    label = "首次登录失败就退出",
                    checked = draft.config.loginFailExit,
                    onCheckedChange = { v -> updateConfig { it.copy(loginFailExit = v) } },
                    supportingText = "关闭（默认）= 一直重连，适合移动网络；开启 = 首次连不上直接退出",
                )
                LabeledTextField(
                    value = draft.config.natHoleStunServer.orEmpty(),
                    onValueChange = { v -> updateConfig { it.copy(natHoleStunServer = v.ifBlank { null }) } },
                    label = "STUN 服务器",
                    placeholder = "留空 = stun.easyvoip.com:3478",
                    supportingText = "只有 xtcp 打洞会用到；默认地址在国内常超时，建议换成可连通的",
                )
            }

            SectionCard(title = "认证") {
                // 无认证在模型里是 auth = null / method = "none"，编码时整段省略（frpc 不接受 none）
                val auth = draft.config.auth ?: AuthConfig(method = "none", token = "")
                DropdownField(
                    label = "认证方式",
                    selected = auth.method,
                    options = listOf("token", "none"),
                    optionLabel = {
                        if (it == "token") "Token（与 frps 的 auth.token 一致）" else "无认证（frps 没开 token）"
                    },
                    onSelect = { v -> updateConfig { it.copy(auth = (it.auth ?: AuthConfig()).copy(method = v)) } },
                )
                if (auth.method == "token") {
                    LabeledTextField(
                        value = auth.token,
                        onValueChange = { v ->
                            updateConfig { it.copy(auth = (it.auth ?: AuthConfig()).copy(token = v)) }
                        },
                        label = "认证 Token",
                        supportingText = "必须与 frps 的 auth.token 完全一致（注意首尾空格）",
                    )
                } else {
                    Text(
                        "frps 没开 auth.token 时选「无认证」；填了 token 的服务器选了它会被拒绝登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LabeledTextField(
                    value = draft.config.metadatas?.get("token").orEmpty(),
                    onValueChange = { v ->
                        // frps 的 multiuser 插件按 metadatas.token 识别用户；空则整段不写
                        updateConfig {
                            it.copy(metadatas = v.trim().ifBlank { null }?.let { t -> mapOf("token" to t) })
                        }
                    },
                    label = "多用户令牌（metadatas.token，可选）",
                    supportingText = "只有 frps 开了 multiuser 插件才需要填；普通服务器留空",
                )
                // 导入的配置可能是 oidc：这里没有对应的输入框，必须明确告诉用户去 toml 改
                if (auth.method != "token" && auth.method != "none") {
                    Text(
                        "当前配置的认证方式是「${auth.method}」，App 不支持编辑；" +
                            "请导出 frpc.toml 后手动改回 token 或 none，否则 frpc 会因缺少 oidc 参数启动失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard(title = "状态监控") {
                SwitchRow(
                    label = "开启状态接口（webServer）",
                    checked = draft.config.webServer != null,
                    onCheckedChange = { enabled ->
                        updateConfig {
                            it.copy(webServer = if (enabled) WebServerConfig() else null)
                        }
                    },
                    supportingText = "只监听 127.0.0.1；App 靠它读取真实远端端口与在线状态",
                )
                draft.config.webServer?.let { ws ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = ws.addr,
                            onValueChange = { v -> updateConfig { it.copy(webServer = ws.copy(addr = v)) } },
                            label = "监听地址",
                            modifier = Modifier.weight(2f),
                        )
                        NumberField(
                            value = adminPortText,
                            onValueChange = { v ->
                                adminPortText = v
                                v.toIntOrNull()?.let { p ->
                                    updateConfig { it.copy(webServer = ws.copy(port = p)) }
                                }
                            },
                            label = "端口",
                            modifier = Modifier.weight(1f),
                            supportingText = "同一台机器跑多份配置时要各用不同端口",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = ws.user,
                            onValueChange = { v -> updateConfig { it.copy(webServer = ws.copy(user = v)) } },
                            label = "面板用户名",
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTextField(
                            value = ws.password,
                            onValueChange = { v -> updateConfig { it.copy(webServer = ws.copy(password = v)) } },
                            label = "面板密码",
                            supportingText = "浏览器打开状态页时要登录",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SectionCard(title = "传输") {
                val transport = draft.config.transport ?: TransportConfig()
                DropdownField(
                    label = "连接协议",
                    selected = transport.protocol,
                    options = listOf("tcp", "kcp", "quic", "websocket", "wss"),
                    optionLabel = { it },
                    onSelect = { v -> updateConfig { it.copy(transport = transport.copy(protocol = v)) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow(
                        label = "启用 TLS",
                        checked = transport.tls?.enable ?: true,
                        onCheckedChange = { v ->
                            updateConfig {
                                it.copy(transport = transport.copy(tls = (transport.tls ?: com.frpc.shared.model.TlsConfig()).copy(enable = v)))
                            }
                        },
                        supportingText = "frp ≥ v0.50 默认开启；frps 关了 TLS 时才需要在这里关掉",
                        modifier = Modifier.weight(1f),
                    )
                    SwitchRow(
                        label = "TCP 多路复用",
                        checked = transport.tcpMux,
                        onCheckedChange = { v ->
                            updateConfig { it.copy(transport = transport.copy(tcpMux = v)) }
                        },
                        supportingText = "必须与 frps 的 tcp_mux 保持一致",
                        modifier = Modifier.weight(1f),
                    )
                }
                NumberField(
                    value = transport.poolCount.toString(),
                    onValueChange = { v ->
                        updateConfig { it.copy(transport = transport.copy(poolCount = v.toIntOrNull() ?: 0)) }
                    },
                    label = "预连接数",
                    supportingText = "预先与 frps 建好的连接数，用来降低首次访问延迟；frp 默认 1",
                )

                // 这些字段平时不用动，默认收起；留空 = 不写入，由 frpc 用自身默认值
                TextButton(onClick = { showTransportAdvanced = !showTransportAdvanced }) {
                    Text(if (showTransportAdvanced) "收起高级连接参数" else "高级连接参数（心跳 / 保活 / 绑定地址）")
                }
                if (showTransportAdvanced) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(
                            value = transport.heartbeatInterval?.toString().orEmpty(),
                            onValueChange = { v ->
                                updateConfig { it.copy(transport = transport.copy(heartbeatInterval = v.toIntOrNull())) }
                            },
                            label = "心跳间隔（秒）",
                            placeholder = "10",
                            supportingText = "留空用 frpc 默认 10",
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = transport.heartbeatTimeout?.toString().orEmpty(),
                            onValueChange = { v ->
                                updateConfig { it.copy(transport = transport.copy(heartbeatTimeout = v.toIntOrNull())) }
                            },
                            label = "心跳超时（秒）",
                            placeholder = "90",
                            supportingText = "超过这么久没心跳就判定断线",
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = transport.tcpMuxKeepaliveInterval?.toString().orEmpty(),
                            onValueChange = { v ->
                                updateConfig {
                                    it.copy(transport = transport.copy(tcpMuxKeepaliveInterval = v.toIntOrNull()))
                                }
                            },
                            label = "多路复用保活（秒）",
                            placeholder = "30",
                            supportingText = "仅 tcpMux 开启时有效",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledTextField(
                        value = transport.connectServerLocalIP.orEmpty(),
                        onValueChange = { v ->
                            updateConfig { it.copy(transport = transport.copy(connectServerLocalIP = v.trim().ifBlank { null })) }
                        },
                        label = "连接 frps 的本地出口 IP（可选）",
                        placeholder = "192.168.1.10",
                        supportingText = "多网卡 / 多出口时指定用哪个源地址连 frps；留空由系统选",
                    )
                    LabeledTextField(
                        value = transport.proxyURL.orEmpty(),
                        onValueChange = { v ->
                            updateConfig { it.copy(transport = transport.copy(proxyURL = v.trim().ifBlank { null })) }
                        },
                        label = "连接代理（proxyURL，可选）",
                        placeholder = "socks5://127.0.0.1:1080",
                        supportingText = "仅 protocol = tcp 有效。App 默认会清掉系统 HTTP_PROXY，要走上游代理只能填这里",
                    )
                }

                HorizontalDivider()
                TextButton(onClick = { showTlsAdvanced = !showTlsAdvanced }) {
                    Text(if (showTlsAdvanced) "收起 TLS 证书" else "TLS 高级（双向认证 / 私有 CA）")
                }
                if (showTlsAdvanced) {
                    val tls = transport.tls ?: com.frpc.shared.model.TlsConfig()
                    FilePathField(
                        value = tls.certFile,
                        onValueChange = { v -> updateConfig { it.copy(transport = transport.copy(tls = tls.copy(certFile = v))) } },
                        label = "客户端证书 certFile",
                        placeholder = "/etc/frp/client.crt",
                        onBrowse = {
                            chooseFile("选择客户端证书", FileDialog.LOAD)?.let { f ->
                                updateConfig { it.copy(transport = transport.copy(tls = tls.copy(certFile = f.absolutePath))) }
                            }
                        },
                    )
                    FilePathField(
                        value = tls.keyFile,
                        onValueChange = { v -> updateConfig { it.copy(transport = transport.copy(tls = tls.copy(keyFile = v))) } },
                        label = "客户端私钥 keyFile",
                        placeholder = "/etc/frp/client.key",
                        onBrowse = {
                            chooseFile("选择客户端私钥", FileDialog.LOAD)?.let { f ->
                                updateConfig { it.copy(transport = transport.copy(tls = tls.copy(keyFile = f.absolutePath))) }
                            }
                        },
                    )
                    FilePathField(
                        value = tls.trustedCaFile,
                        onValueChange = { v -> updateConfig { it.copy(transport = transport.copy(tls = tls.copy(trustedCaFile = v))) } },
                        label = "CA 证书 trustedCaFile",
                        placeholder = "/etc/frp/ca.crt",
                        supportingText = "frps 用自签证书时填它的 CA，否则会报证书校验失败",
                        onBrowse = {
                            chooseFile("选择 CA 证书", FileDialog.LOAD)?.let { f ->
                                updateConfig { it.copy(transport = transport.copy(tls = tls.copy(trustedCaFile = f.absolutePath))) }
                            }
                        },
                    )
                    SwitchRow(
                        label = "发送自定义 TLS 首字节",
                        // frp 默认 disableCustomTLSFirstByte = true（不发送），所以开关默认关闭
                        checked = tls.disableCustomTLSFirstByte == false,
                        onCheckedChange = { on ->
                            updateConfig {
                                it.copy(transport = transport.copy(tls = tls.copy(disableCustomTLSFirstByte = !on)))
                            }
                        },
                        supportingText = "对应 frp 的 disableCustomTLSFirstByte。默认不发送；只有老版本 frps 要求时才打开",
                    )
                }

                SwitchRow(
                    label = "自定义传输配置",
                    checked = draft.config.transport != null,
                    onCheckedChange = { enabled ->
                        updateConfig { it.copy(transport = if (enabled) TransportConfig() else null) }
                    },
                    supportingText = "关闭时不写 transport 段，全部用 frpc 默认值",
                )
            }

            SectionCard(title = "日志（frpc 自身）") {
                val log = draft.config.log
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(
                        label = "日志级别",
                        selected = log?.level ?: "info",
                        options = listOf("trace", "debug", "info", "warn", "error"),
                        optionLabel = { it },
                        onSelect = { v ->
                            updateConfig { it.copy(log = (log ?: LogConfig()).copy(level = v)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = (log?.maxDays ?: 3).toString(),
                        onValueChange = { v ->
                            updateConfig { it.copy(log = (log ?: LogConfig()).copy(maxDays = v.toIntOrNull() ?: 3)) }
                        },
                        label = "日志保留天数",
                        supportingText = "只对写入文件的日志有效",
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow(
                    label = "关闭控制台颜色",
                    checked = log?.disablePrintColor == true,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(log = (log ?: LogConfig()).copy(disablePrintColor = v)) }
                    },
                    supportingText = "App 自己给日志着色，关掉可少一堆 ANSI 转义字符",
                )
                Text(
                    "日志固定输出到 console，由 App 采集后在「日志」页实时展示（排障时切到 debug）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------------------------------------------------- 代理规则
            SectionCard(
                title = "代理规则（${draft.config.proxies.size}）",
                trailing = {
                    Button(onClick = { proxyCreating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("添加代理", modifier = Modifier.padding(start = 6.dp))
                    }
                },
            ) {
                Text(
                    "把本机服务暴露到 frps：tcp/udp 走端口，http/https/tcpmux 走域名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.config.proxies.isEmpty()) {
                    Text(
                        "还没有代理规则：点右上角「添加代理」，或到「配置」页导入现成的 frpc.toml",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (draft.config.proxies.size > 5) {
                    // 规则一多就没法一眼找到，给个本地过滤（不影响索引：下面用 withIndex 保留原始下标）
                    LabeledTextField(
                        value = proxyQuery,
                        onValueChange = { proxyQuery = it },
                        label = "筛选代理（名称 / 类型 / 端口）",
                    )
                }
                draft.config.proxies.withIndex()
                    .filter { (_, p) ->
                        proxyQuery.isBlank() ||
                            p.name.contains(proxyQuery, ignoreCase = true) ||
                            p.type.contains(proxyQuery, ignoreCase = true) ||
                            p.localPort?.toString()?.contains(proxyQuery) == true ||
                            p.remotePort?.toString()?.contains(proxyQuery) == true
                    }
                    .forEach { (index, proxy) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(proxy.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = describeProxy(proxy),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { proxyEditing = index }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = {
                            updateConfig { it.copy(proxies = it.proxies.filterIndexed { i, _ -> i != index }) }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 访问者
            SectionCard(
                title = "访问者（${draft.config.visitors.size}）",
                trailing = {
                    OutlinedButton(onClick = { visitorEditing = -1 }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("添加访问者", modifier = Modifier.padding(start = 6.dp))
                    }
                },
            ) {
                Text(
                    "反向用法：本机主动连接别人的 stcp/sudp/xtcp 私密代理，并在本机开一个端口给本地程序用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.config.visitors.forEachIndexed { index, visitor ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(visitor.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = describeVisitor(visitor, draft.config.user),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { visitorEditing = index }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = {
                            updateConfig { it.copy(visitors = it.visitors.filterIndexed { i, _ -> i != index }) }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }

            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (proxyCreating || proxyEditing != null) {
        val existing = proxyEditing?.let { draft.config.proxies[it] }
        // 批量生成的名字要和已有代理查重；编辑时排除自己
        val taken = draft.config.proxies.filterIndexed { i, _ -> i != proxyEditing }.map { it.name }.toSet()
        ProxyEditDialog(
            initial = existing,
            localUser = draft.config.user,
            takenNames = taken,
            onDismiss = { proxyCreating = false; proxyEditing = null },
            onSave = { created ->
                val list = draft.config.proxies.toMutableList()
                val index = proxyEditing
                if (existing == null || index == null) {
                    list.addAll(created)
                } else {
                    // 编辑时改成了批量：原位替换第一条，其余插在它后面
                    list[index] = created.first()
                    if (created.size > 1) list.addAll(index + 1, created.drop(1))
                }
                updateConfig { it.copy(proxies = list) }
                proxyCreating = false
                proxyEditing = null
            },
        )
    }

    if (visitorEditing != null) {
        val index = visitorEditing!!
        val existing = draft.config.visitors.getOrNull(index)
        VisitorEditDialog(
            initial = existing,
            localUser = draft.config.user,
            onDismiss = { visitorEditing = null },
            onSave = { visitor ->
                val list = draft.config.visitors.toMutableList()
                if (index < 0 || existing == null) list.add(visitor) else list[index] = visitor
                updateConfig { it.copy(visitors = list) }
                visitorEditing = null
            },
        )
    }

    // 运行中保存的三岔口：立刻重载 / 只存盘 / 取消
    if (confirmSaveRunning) {
        AlertDialog(
            onDismissRequest = { confirmSaveRunning = false },
            title = { Text("保存运行中的配置") },
            text = { Text("保存后 frpc 仍在使用旧配置。是否立即重载让它生效？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSaveRunning = false
                    save(reload = true)
                }) { Text("保存并重载") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmSaveRunning = false
                        save(reload = false)
                    }) { Text("仅保存") }
                    TextButton(onClick = { confirmSaveRunning = false }) { Text("取消") }
                }
            },
        )
    }
}

/**
 * 代理摘要：本地服务 → 对外入口。
 * 对外入口按 frp 类型区分：tcp/udp 是 frps 上的端口，http/https/tcpmux 是域名，
 * stcp/sudp/xtcp 不对外暴露，只能由访问者接入。
 */
private fun describeProxy(proxy: ProxyConfig): String = buildString {
    append(proxy.type.uppercase())
    val type = proxy.proxyType
    if (type?.needsLocalAddr == true) {
        append(" · 本地 ").append(proxy.localIP ?: "127.0.0.1").append(":").append(proxy.localPort ?: "?")
    }
    when {
        type?.needsRemotePort == true -> {
            append(" → ")
            append(
                if (proxy.remotePort == null || proxy.remotePort == 0) "远程端口随机"
                else "远程 :${proxy.remotePort}",
            )
        }
        type?.domainBased == true -> {
            val domain = proxy.customDomains?.firstOrNull() ?: proxy.subdomain
            append(" → ").append(domain ?: "域名待配置")
        }
        else -> append(" → 私密代理（由访问者接入）")
    }
    if (proxy.enabled == false) append(" · 已禁用")
}

/** 访问者摘要：本机监听 → frps 上实际查找的代理名（含 serverUser / 本机 user 前缀） */
private fun describeVisitor(visitor: VisitorConfig, localUser: String?): String = buildString {
    append(visitor.type.uppercase())
    if (visitor.bindPort < 0) {
        append(" · 不监听端口")
    } else {
        append(" · 本地 ").append(visitor.bindAddr).append(":").append(visitor.bindPort)
    }
    append(" → ")
    append(
        com.frpc.shared.model.FrpcNaming.targetServerProxyName(localUser, visitor.serverUser, visitor.serverName),
    )
}

/** 字段路径 → 用户能看懂的名称（P3：错误文案不直接暴露 serverPort / auth.token 这类原始字段名） */
private fun fieldDisplayName(field: String): String = when (field.substringAfterLast('.').substringAfter(']')) {
    "serverAddr" -> "服务器地址"
    "serverPort" -> "服务器端口"
    "user" -> "代理名前缀"
    "clientID" -> "客户端标识"
    "token" -> "认证 Token"
    "method" -> "认证方式"
    "addr" -> "监听地址"
    "port" -> "端口"
    "protocol" -> "连接协议"
    "poolCount" -> "预连接数"
    "bandwidthLimit" -> "带宽限制"
    "proxyProtocolVersion" -> "PROXY Protocol"
    "name" -> "名称"
    "type" -> "类型"
    "localIP" -> "本地地址"
    "localPort" -> "本地端口"
    "remotePort" -> "远程端口"
    "subdomain" -> "子域名"
    "customDomains" -> "自定义域名"
    "locations" -> "路由路径"
    "httpUser" -> "HTTP 账号"
    "httpPassword" -> "HTTP 密码"
    "secretKey" -> "私密密钥"
    "multiplexer" -> "多路复用器"
    "serverName" -> "对方代理名"
    "serverUser" -> "对方设备前缀"
    "bindAddr" -> "监听地址"
    "bindPort" -> "本地监听端口"
    "level" -> "日志级别"
    "natHoleStunServer" -> "STUN 服务器"
    "fallbackTo" -> "打洞失败转交给"
    "fallbackTimeoutMs" -> "打洞等待时长"
    "certFile" -> "客户端证书"
    "keyFile" -> "客户端私钥"
    "trustedCaFile" -> "CA 证书"
    "heartbeatInterval" -> "心跳间隔"
    "heartbeatTimeout" -> "心跳超时"
    "tcpMuxKeepaliveInterval" -> "多路复用保活间隔"
    "plugin" -> "插件"
    "localAddr" -> "插件本地地址"
    "crtPath" -> "证书文件"
    "keyPath" -> "密钥文件"
    "maxDays" -> "日志保留天数"
    else -> field
}

fun validationText(issue: ValidationIssue): String {
    val where = when (issue.scope) {
        Scope.GLOBAL -> "全局"
        Scope.AUTH -> "认证"
        Scope.WEB_SERVER -> "状态监控"
        Scope.TRANSPORT -> "传输"
        Scope.LOG -> "日志"
        Scope.PROXY -> "代理「${issue.entityName ?: ""}」"
        Scope.VISITOR -> "访问者「${issue.entityName ?: ""}」"
    }
    val label = fieldDisplayName(issue.field)
    val what = when (issue.reason) {
        Reason.EMPTY_REQUIRED_FIELD -> "$label 不能为空"
        Reason.PORT_OUT_OF_RANGE -> "$label 需在 1-65535"
        Reason.PORT_IN_USE -> "$label 已被占用，换一个端口"
        Reason.INVALID_VALUE -> "$label 取值不合法"
        Reason.DUPLICATE_NAME -> "代理名「${issue.entityName ?: ""}」重复，frps 上必须唯一"
        Reason.TYPE_FIELD_MISSING -> "该类型缺少必填字段"
        Reason.DOMAIN_OR_SUBDOMAIN_REQUIRED -> "需要自定义域名或子域名（frp 要求至少填一个）"
        Reason.UNSUPPORTED_VALUE -> "$label 暂不支持，请导出 frpc.toml 手动配置"
        Reason.FIELD_NOT_SUPPORTED_FOR_TYPE -> "$label 不适用于该类型（frp 严格解码，写进去会启动失败）"
        Reason.BIND_PORT_REQUIRED -> "本地监听端口不能为 0（frp 会报 bind port is required）；不监听请填 -1"
        Reason.SUBDOMAIN_INVALID -> "子域名不能包含 . 或 *（通配请用自定义域名）"
        Reason.MULTIPLEXER_INVALID -> "多路复用器只能是 httpconnect"
        Reason.BANDWIDTH_LIMIT_INVALID -> "带宽限制必须带 KB 或 MB 单位，例如 1MB"
        Reason.FALLBACK_VISITOR_NOT_FOUND -> "打洞失败转交的访问者不存在，打洞失败时没有退路"
        Reason.SERVER_NAME_PREFIX_DOUBLED -> "$label 只填代理名。前缀取「对方设备前缀」，留空则用本机前缀"
        Reason.XTCP_STUN_SERVER_NOT_SET ->
            "用了 xtcp 却没填 STUN 服务器：frpc 会退回默认 stun.easyvoip.com:3478，" +
                "该地址经常超时，会导致打洞失败（访问者侧报 context deadline exceeded）。" +
                "建议填一个能连通的，例如 stun.miwifi.com:3478"
    }
    return "$where：$what" + (issue.detail?.let { "（$it）" } ?: "")
}
