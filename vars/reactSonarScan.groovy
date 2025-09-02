#!/usr/bin/env groovy

/**
 * React Static Code Analysis with SonarQube
 * @param config Map of configuration parameters
 * @return Current build result
 */
def call(Map config = [:]) {
    // Default configuration
    def defaults = [
        projectKey: '',
        projectName: '',
        sonarUrl: 'http://localhost:9000',
        repoUrl: '',
        branch: 'main',
        nodeTool: 'NodeJS',
        sonarTool: 'SonarScanner',
        sonarInstance: 'SonarQube',
        qualityGateTimeout: 5,
        enableQualityGate: false,
        cleanWorkspace: true,
        installCommand: 'npm install',
        testCommand: 'npm test',
        notifyEmail: '',
        exclusions: '**/node_modules/**,**/*.spec.js,**/*.spec.jsx,**/*.spec.ts,**/*.spec.tsx,**/coverage/**,**/build/**,**/dist/**',
        coverage: false,
        coveragePath: 'coverage/lcov.info',
        typescript: false
    ]
    
    // Merge provided config with defaults
    config = defaults + config
    
    // Validate required parameters
    if (!config.projectKey) {
        error 'Project key is required'
    }
    
    if (!config.projectName) {
        config.projectName = config.projectKey
    }
    
    // For storing build results
    def QUALITY_GATE_STATUS = 'Not Run'
    
    // Tool setup
    def nodeJSHome = tool config.nodeTool
    def scannerHome = tool config.sonarTool
    
    try {
        stage('Checkout') {
            checkout scm
            
            // If specific repository URL is provided, use that instead
            if (config.repoUrl) {
                git url: config.repoUrl, branch: config.branch
            }
        }
        
        stage('Install Dependencies') {
            sh config.installCommand
        }
        
        if (config.testCommand != null && config.testCommand != '') {
            stage('Run Tests') {
                try {
                    sh config.testCommand
                } catch (Exception e) {
                    echo "Tests failed but continuing with analysis: ${e.message}"
                }
            }
        }
        
        stage('SonarQube Analysis') {
            // Build the scanner command with all necessary options
            def scannerCommand = "${scannerHome}/bin/sonar-scanner \\\n"
            scannerCommand += "-Dsonar.projectKey=${config.projectKey} \\\n"
            scannerCommand += "-Dsonar.projectName='${config.projectName}' \\\n"
            scannerCommand += "-Dsonar.sources=. \\\n"
            scannerCommand += "-Dsonar.sourceEncoding=UTF-8 \\\n"
            scannerCommand += "-Dsonar.host.url=${config.sonarUrl} \\\n"
            scannerCommand += "-Dsonar.exclusions=${config.exclusions} \\\n"
            scannerCommand += "-Dsonar.links.homepage=${config.repoUrl ?: env.GIT_URL ?: ''}"
            
            // Add TypeScript specific settings if enabled
            if (config.typescript) {
                scannerCommand += " \\\n-Dsonar.typescript.lcov.reportPaths=${config.coveragePath}"
                scannerCommand += " \\\n-Dsonar.typescript.tsconfigPath=tsconfig.json"
            }
            
            // Add coverage settings if enabled
            if (config.coverage) {
                scannerCommand += " \\\n-Dsonar.javascript.lcov.reportPaths=${config.coveragePath}"
            }
            
            withSonarQubeEnv(config.sonarInstance) {
                sh scannerCommand
            }
            
            env.SONAR_REPORT_URL = "${config.sonarUrl}/dashboard?id=${config.projectKey}"
        }
        
        if (config.enableQualityGate) {
            stage('Quality Gate') {
                timeout(time: config.qualityGateTimeout, unit: 'MINUTES') {
                    try {
                        def qg = waitForQualityGate abortPipeline: false
                        QUALITY_GATE_STATUS = qg.status
                        
                        if (qg.status != 'OK') {
                            error "Quality Gate failed with status: ${qg.status}"
                        }
                    } catch (Exception e) {
                        echo "Error waiting for Quality Gate: ${e.message}"
                        
                        // Try direct API call as fallback
                        echo "Attempting direct API call to SonarQube..."
                        def response = sh(
                            script: "curl -s ${config.sonarUrl}/api/qualitygates/project_status?projectKey=${config.projectKey}",
                            returnStdout: true
                        ).trim()
                        
                        echo "SonarQube API Response: ${response}"
                        
                        if (response.contains('"status":"OK"')) {
                            QUALITY_GATE_STATUS = "OK"
                        } else {
                            QUALITY_GATE_STATUS = "FAILED"
                            error "Quality Gate failed (via API)"
                        }
                    }
                }
            }
        }
        
        // Build successful
        currentBuild.result = 'SUCCESS'
        echo 'SonarQube analysis completed successfully!'
        return [status: 'SUCCESS', qualityGate: QUALITY_GATE_STATUS]
        
    } catch (Exception e) {
        // Build failed
        currentBuild.result = 'FAILURE'
        echo "SonarQube analysis failed: ${e.message}"
        return [status: 'FAILURE', qualityGate: QUALITY_GATE_STATUS, error: e.message]
        
    } finally {
        // Always send notification if email is provided
        def currentResult = currentBuild.result ?: 'UNKNOWN'
        
        if (config.notifyEmail) {
            def now = new Date().format('yyyy-MM-dd HH:mm:ss', TimeZone.getTimeZone('UTC'))
            
            def emailBody = """
            <html>
            <head>
                <style>
                    body { font-family: Arial, sans-serif; }
                    .header { background-color: #f2f2f2; padding: 10px; border-bottom: 1px solid #ddd; }
                    .success { color: green; }
                    .failure { color: red; }
                    .warning { color: orange; }
                    .container { padding: 15px; }
                    table { border-collapse: collapse; width: 100%; }
                    table, th, td { border: 1px solid #ddd; }
                    th, td { padding: 8px; text-align: left; }
                    th { background-color: #f2f2f2; }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>SonarQube Analysis Results</h1>
                </div>
                <div class="container">
                    <table>
                        <tr>
                            <th>Project</th>
                            <td>${config.projectName}</td>
                        </tr>
                        <tr>
                            <th>Repository</th>
                            <td><a href="${config.repoUrl ?: env.GIT_URL ?: 'Not available'}">${config.repoUrl ?: env.GIT_URL ?: 'Not available'}</a></td>
                        </tr>
                        <tr>
                            <th>Build Status</th>
                            <td class="${currentResult == 'SUCCESS' ? 'success' : 'failure'}">${currentResult}</td>
                        </tr>
                        <tr>
                            <th>Quality Gate Status</th>
                            <td class="${QUALITY_GATE_STATUS == 'OK' ? 'success' : QUALITY_GATE_STATUS == 'Not Run' ? 'warning' : 'failure'}">${QUALITY_GATE_STATUS}</td>
                        </tr>
                        <tr>
                            <th>Date & Time (UTC)</th>
                            <td>${now}</td>
                        </tr>
                        <tr>
                            <th>Build URL</th>
                            <td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td>
                        </tr>
                        <tr>
                            <th>SonarQube Report</th>
                            <td><a href="${env.SONAR_REPORT_URL ?: ''}">${env.SONAR_REPORT_URL ? 'View Detailed Report' : 'Not available'}</a></td>
                        </tr>
                    </table>
                </div>
            </body>
            </html>
            """
            
            emailext(
                subject: "${currentResult}: SonarQube Analysis for ${config.projectName}",
                body: emailBody,
                to: config.notifyEmail,
                mimeType: 'text/html',
                attachLog: true
            )
        }
        
        if (config.cleanWorkspace) {
            cleanWs()
        }
    }
}
