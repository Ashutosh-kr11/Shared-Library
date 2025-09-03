#!/usr/bin/env groovy

/**
 * Python Static Code Analysis with SonarQube
 * 
 * @param config Map of configuration parameters
 * @return Current build result
 */
def call(Map config = [:]) {
    // Default configuration
    def defaults = [
        projectKey: '',
        projectName: '',
        sonarUrl: 'http://localhost:9000',
        repoUrl: '',  // Optional when using Pipeline script from SCM
        branch: 'main',
        sonarTool: 'SonarScanner',
        sonarInstance: 'SonarQube',
        //qualityGateTimeout: 5,
        //enableQualityGate: true,
        cleanWorkspace: true,
        notifyEmail: 'ashutosh.devpro@gmail.com',
        pythonVersion: '3',
        exclusions: '**/venv/**,**/migrations/**,**/*.pyc,**/__pycache__/**,**/tests/**,setup.py',
        coverage: false,
        coveragePath: 'coverage.xml'
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
    
    node {
        // For storing build results
        def QUALITY_GATE_STATUS = 'Not Run'
        def SONAR_REPORT_URL = ''
        
        // Tool setup - MOVED INSIDE NODE BLOCK
        def scannerHome = tool config.sonarTool
        
        try {
            stage('Checkout') {
                // Use checkout scm if no repoUrl is provided (Pipeline from SCM)
                // Otherwise use git checkout with the provided URL
                if (config.repoUrl) {
                    git url: config.repoUrl, branch: config.branch
                    env.REPOSITORY_URL = config.repoUrl
                } else {
                    checkout scm
                    // Try to get the repository URL from the current build
                    try {
                        env.REPOSITORY_URL = sh(script: 'git config --get remote.origin.url', returnStdout: true).trim()
                    } catch (Exception e) {
                        echo "Could not determine repository URL: ${e.message}"
                        env.REPOSITORY_URL = 'Not available'
                    }
                }
                
                echo "Using repository: ${env.REPOSITORY_URL}"
            }
            
            stage('SonarQube Analysis') {
                // Build the scanner command with all necessary options
                def scannerCommand = "${scannerHome}/bin/sonar-scanner \\\n"
                scannerCommand += "-Dsonar.projectKey=${config.projectKey} \\\n"
                scannerCommand += "-Dsonar.projectName='${config.projectName}' \\\n"
                scannerCommand += "-Dsonar.sources=. \\\n"
                scannerCommand += "-Dsonar.sourceEncoding=UTF-8 \\\n"
                scannerCommand += "-Dsonar.host.url=${config.sonarUrl} \\\n"
                scannerCommand += "-Dsonar.python.version=${config.pythonVersion} \\\n"
                scannerCommand += "-Dsonar.exclusions=${config.exclusions} \\\n"
                scannerCommand += "-Dsonar.links.homepage=${env.REPOSITORY_URL ?: 'Not available'}"
                
                // Add coverage settings if enabled
                if (config.coverage) {
                    scannerCommand += " \\\n-Dsonar.python.coverage.reportPaths=${config.coveragePath}"
                }
                
                // Run the SonarQube analysis
                withSonarQubeEnv(config.sonarInstance) {
                    sh scannerCommand
                }
                
                SONAR_REPORT_URL = "${config.sonarUrl}/dashboard?id=${config.projectKey}"
                echo "SonarQube report available at: ${SONAR_REPORT_URL}"
            }
            
            /** if (config.enableQualityGate) {
                stage('Quality Gate') {
                    timeout(time: config.qualityGateTimeout, unit: 'MINUTES') {
                        try {
                            def qg = waitForQualityGate abortPipeline: false
                            QUALITY_GATE_STATUS = qg.status
                            
                            if (qg.status != 'OK') {
                                echo "Quality Gate failed with status: ${qg.status}"
                            }
                        } catch (Exception e) {
                            echo "Error waiting for Quality Gate: ${e.message}"
                            QUALITY_GATE_STATUS = "ERROR"
                        }
                    }
                }
            } **/
            
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
                
                // Extract repository name from URL
                def repoName = 'Not available'
                if (env.REPOSITORY_URL) {
                    try {
                        def urlParts = env.REPOSITORY_URL.tokenize('/')
                        def org = urlParts[-2]
                        def repo = urlParts[-1].replace('.git', '')
                        repoName = "${org}/${repo}"
                    } catch (Exception e) {
                        repoName = env.REPOSITORY_URL
                    }
                }
                
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
                                <td>${repoName}</td>
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
                                <td><a href="${SONAR_REPORT_URL ?: config.sonarUrl}">View Detailed Report</a></td>
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
}
