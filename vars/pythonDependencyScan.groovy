#!/usr/bin/env groovy

def call(Map config = [:]) {
    // Default configuration
    def reportName = config.reportName ?: 'dependency_scan_report.txt'
    def venvDir = config.venvDir ?: 'venv'
    def emailRecipients = config.emailRecipients
    
    // Initialize result map
    def scanResults = [
        vulnerabilitiesFound: 0,
        scanReport: reportName,
        successful: false
    ]
    
    // Run the scan
    node {
        try {
            stage('Checkout') {
                checkout scm
            }
            
            stage('Setup Virtual Environment') {
                sh "python3 -m venv ${venvDir}"
                sh """
                    # Activate virtual environment and install tools
                    . ${venvDir}/bin/activate
                    pip install --upgrade pip
                    pip install pip-audit safety tomli
                """
            }
            
            stage('Scan Dependencies') {
                sh """
                    # Activate virtual environment
                    . ${venvDir}/bin/activate
                    
                    # Create report file
                    echo "Python Dependency Scan Report - \$(date '+%Y-%m-%d %H:%M:%S')" > ${reportName}
                    echo "=======================================" >> ${reportName}
                    
                    # Check for requirements.txt
                    if [ -f requirements.txt ]; then
                        echo -e "\\n\\n## REQUIREMENTS.TXT FOUND ##" >> ${reportName}
                        echo -e "Content of requirements.txt:" >> ${reportName}
                        cat requirements.txt >> ${reportName}
                        
                        echo -e "\\n\\n## SCANNING WITH PIP-AUDIT ##" >> ${reportName}
                        pip-audit --requirement requirements.txt --format text >> ${reportName} 2>&1 || echo -e "\\nPip-audit scan completed with issues" >> ${reportName}
                        
                        echo -e "\\n\\n## SCANNING WITH SAFETY ##" >> ${reportName}
                        safety check -r requirements.txt --output text >> ${reportName} 2>&1 || echo -e "\\nSafety scan completed with issues" >> ${reportName}
                    else
                        echo -e "\\n\\nNo requirements.txt found" >> ${reportName}
                    fi
                    
                    # Check for pyproject.toml
                    if [ -f pyproject.toml ]; then
                        echo -e "\\n\\n## PYPROJECT.TOML FOUND ##" >> ${reportName}
                        echo -e "Content of pyproject.toml:" >> ${reportName}
                        cat pyproject.toml >> ${reportName}
                        
                        # Extract dependencies from pyproject.toml
                        python -c "
import tomli
import sys
try:
    with open('pyproject.toml', 'rb') as f:
        data = tomli.load(f)
    deps = []
    # Try to find dependencies in various locations
    if 'dependencies' in data.get('project', {}):
        deps.extend(data['project']['dependencies'])
        print('Found PEP 621 dependencies')
    elif 'dependencies' in data.get('tool', {}).get('poetry', {}):
        deps = list(data['tool']['poetry']['dependencies'].keys())
        deps = [d for d in deps if d != 'python']
        print('Found Poetry dependencies')
    elif 'dependencies' in data:
        deps = list(data['dependencies'].keys())
        print('Found direct dependencies')
    
    # Write to temp requirements file
    if deps:
        with open('pyproject_deps.txt', 'w') as f:
            for dep in deps:
                if not dep.startswith('python'):
                    f.write(f'{dep}\\\\n')
        print(f'Dependencies extracted: {deps}')
    else:
        print('No dependencies found in pyproject.toml')
except Exception as e:
    print(f'Error processing pyproject.toml: {e}')
" >> ${reportName} 2>&1
                        
                        # Scan the extracted dependencies
                        if [ -f pyproject_deps.txt ]; then
                            echo -e "\\n\\n## SCANNING EXTRACTED DEPENDENCIES WITH PIP-AUDIT ##" >> ${reportName}
                            pip-audit --requirement pyproject_deps.txt --format text >> ${reportName} 2>&1 || echo -e "\\nPip-audit scan completed with issues" >> ${reportName}
                            
                            echo -e "\\n\\n## SCANNING EXTRACTED DEPENDENCIES WITH SAFETY ##" >> ${reportName}
                            safety check -r pyproject_deps.txt --output text >> ${reportName} 2>&1 || echo -e "\\nSafety scan completed with issues" >> ${reportName}
                        fi
                    else
                        echo -e "\\n\\nNo pyproject.toml found" >> ${reportName}
                    fi
                    
                    # Scan installed packages in the environment
                    echo -e "\\n\\n## SCANNING ALL INSTALLED PACKAGES ##" >> ${reportName}
                    echo -e "Installed packages:" >> ${reportName}
                    pip freeze >> ${reportName}
                    
                    echo -e "\\n\\n## SCANNING INSTALLED PACKAGES WITH PIP-AUDIT ##" >> ${reportName}
                    pip-audit --format text >> ${reportName} 2>&1 || echo -e "\\nPip-audit scan completed with issues" >> ${reportName}
                    
                    echo -e "\\n\\n## SCANNING INSTALLED PACKAGES WITH SAFETY ##" >> ${reportName}
                    safety check --output text >> ${reportName} 2>&1 || echo -e "\\nSafety scan completed with issues" >> ${reportName}
                    
                    # Add a comprehensive summary section
                    echo -e "\\n\\n## SUMMARY ##" >> ${reportName}
                    echo "Timestamp: \$(date '+%Y-%m-%d %H:%M:%S')" >> ${reportName}
                    echo -e "Repository: \$(git config --get remote.origin.url)" >> ${reportName}
                    
                    # Count vulnerabilities for summary
                    echo -e "\\n## VULNERABILITY FINDINGS ##" >> ${reportName}
                    VULN_COUNT=\$(grep -i -c "vulnerability" ${reportName} || echo "0")
                    echo "Found approximately \${VULN_COUNT} references to vulnerabilities in the scan report." >> ${reportName}
                    echo "\${VULN_COUNT}" > vuln_count.txt
                    
                    # Extract vulnerability highlights
                    if [ "\${VULN_COUNT}" -gt "0" ]; then
                        echo -e "\\n## VULNERABILITY HIGHLIGHTS ##" >> ${reportName}
                        grep -i -A 2 -B 1 "vulnerability" ${reportName} | head -n 20 >> ${reportName} || true
                        echo -e "\\n(See full report for complete details)" >> ${reportName}
                    else
                        echo -e "\\nNo obvious vulnerabilities were detected in the scan." >> ${reportName}
                    fi
                """
                
                // Read vulnerability count for result map
                if (fileExists('vuln_count.txt')) {
                    scanResults.vulnerabilitiesFound = sh(script: 'cat vuln_count.txt', returnStdout: true).trim() as Integer
                }
            }
            
            scanResults.successful = true
        } 
        catch (Exception e) {
            currentBuild.result = 'FAILURE'
            scanResults.errorMessage = e.getMessage()
            echo "Error during dependency scan: ${e.getMessage()}"
        } 
        finally {
            // Archive artifacts
            archiveArtifacts artifacts: reportName, allowEmptyArchive: true
            scanResults.reportUrl = "${BUILD_URL}artifact/${reportName}"
            
            // Send email if configured
            if (emailRecipients) {
                emailext(
                    subject: "Python Dependency Scan Results - ${currentBuild.result}",
                    body: "Python dependency scan completed with result: ${currentBuild.result}\n\nSee the report for details: ${BUILD_URL}artifact/${reportName}",
                    attachmentsPattern: reportName,
                    to: emailRecipients,
                    mimeType: 'text/plain'
                )
            }
            
            // Cleanup
            sh "rm -rf ${venvDir} pyproject_deps.txt vuln_count.txt || true"
        }
    }
    
    return scanResults
}
