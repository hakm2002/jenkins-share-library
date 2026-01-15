def call(Map config = [:]) {
    // --- 1. CONFIGURATION & DEFAULTS ---
    // Mengambil parameter dari Jenkinsfile, atau pakai default jika kosong
    def appName = config.appName ?: env.JOB_BASE_NAME
    def projectDir = config.projectDir ?: '.' // Folder sub-direktori (misal: 'To-do-list')
    def dockerRegistryUser = config.dockerUser ?: 'hakm2002'
    def dockerImage = "${dockerRegistryUser}/${appName}"
    def deployPort = config.deployPort ?: '8080'
    
    // Credential IDs (sesuaikan dengan yang ada di Jenkins)
    def dockerCredId = config.dockerCredId ?: 'docker-hub'
    def sonarCredId = config.sonarCredId ?: 'go-token'
    
    // Environment Variables untuk container saat deploy (Map)
    def appEnvVars = config.appEnvVars ?: [:] 

    pipeline {
        agent any

        environment {
            // Setup Scanner Home (Pastikan nama 'sonar' sesuai Global Tool Config)
            SCANNER_HOME = tool 'sonar' 
        }

        
            stage('SonarQube Analysis') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Running logic inside: ${projectDir}"
                            
                            // 1. Generate Coverage (Wajib agar coverage % muncul di Sonar)
                            try {
                                echo "Running Go Test & Coverage..."
                                sh 'go mod tidy'
                                // Output coverage disimpan ke 'coverage.out'
                                sh 'go test ./... -coverprofile=coverage.out'
                            } catch (Exception e) {
                                echo 'Warning: Unit test failed, but continuing scan...'
                            }

                            // 2. Definisi Parameter Sonar
                            // Kita pakai appName sebagai projectKey agar otomatis
                            def scannerParams = [
                                "-Dsonar.projectKey=${appName}",
                                "-Dsonar.projectName=${appName}",
                                "-Dsonar.sources=.",
                                "-Dsonar.exclusions=**/*_test.go,**/vendor/**",
                                "-Dsonar.go.coverage.reportPaths=coverage.out",
                                "-Dsonar.language=go"
                            ].join(' ')

                            // 3. Jalankan Scanner
                            withCredentials([string(credentialsId: sonarCredId, variable: 'SONAR_TOKEN')]) {
                                // Pastikan nama 'SonarQube' sesuai dengan nama Server di:
                                // Manage Jenkins > System > SonarQube servers
                                withSonarQubeEnv('SonarQube') { 
                                    sh "${env.SCANNER_HOME}/bin/sonar-scanner ${scannerParams} -Dsonar.token=\${SONAR_TOKEN}"
                                }
                            }
                        }
                    }
                }
            }
            
            // Stage 2: Build Docker Image
            stage('Build Docker Image') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Building Docker Image: ${dockerImage}"
                            sh "docker build -t ${dockerImage}:latest ."
                        }
                    }
                }
            }

            // Stage 3: Push ke Registry
            stage('Push to Docker Hub') {
                steps {
                    withCredentials([usernamePassword(credentialsId: dockerCredId, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                        sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
                        sh "docker push ${dockerImage}:latest"
                    }
                }
            }

            // Stage 4: Deploy (Simple Docker Run)
            // Catatan: Ini akan deploy di server Jenkins/Agent itu sendiri
            stage('Deploy to Server') {
                steps {
                    script {
                        def containerName = "${appName}"
                        
                        // Konversi Map environment variables menjadi string "-e KEY=VALUE"
                        def envString = appEnvVars.collect { k, v -> "-e ${k}=${v}" }.join(' ')

                        echo "Deploying container: ${containerName} on port ${deployPort}"
                        
                        // Cleanup container lama
                        sh "docker network create devops-network || true"
                        sh "docker stop ${containerName} || true"
                        sh "docker rm ${containerName} || true"

                        // Run container baru
                        sh """
                        docker run -d \
                        --name ${containerName} \
                        --network devops-network \
                        -p ${deployPort}:8080 \
                        ${envString} \
                        ${dockerImage}:latest
                        """
                    }
                }
            }
        }

        post {
            always { 
                sh "docker logout || true" 
                cleanWs()
            }
        }
    }
}
