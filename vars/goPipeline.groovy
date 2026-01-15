def call(Map config = [:]) {
    // Definisikan variabel default jika user lupa isi
    def appName = config.appName ?: 'my-go-app'
    def projectDir = config.projectDir ?: '.' 
    def deployPort = config.deployPort ?: '8080'
    def sonarCredId = config.sonarCredId ?: 'sonar-token' // Pastikan ID ini ada di Credentials Jenkins
    def dockerRegistry = config.dockerRegistry ?: 'docker.io' // Optional
    def imageTag = "latest"

    pipeline {
        agent any

        environment {
            // Environment variable global
            GO111MODULE = 'on'
            CGO_ENABLED = '0'
            PATH = "/usr/local/go/bin:${env.PATH}"
            // Sesuaikan nama credential ID Dockerhub Anda di Jenkins
            DOCKER_CREDENTIAL_ID = 'dockerhub-id-hakm' 
        }

        stages {
            stage('Checkout Scm') {
                steps {
                    // Checkout code dari repo aplikasi
                    checkout scm
                }
            }

            stage('Unit Test & Coverage') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Running Unit Tests in ${projectDir}..."
                            sh 'go mod tidy'
                            // Output coverage ke coverage.out agar dibaca Sonar
                            sh 'go test ./... -coverprofile=coverage.out'
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Starting SonarQube Analysis..."
                            
                            // Definisi Parameter SonarScanner
                            def scannerParams = [
                                "-Dsonar.projectKey=${appName}",
                                "-Dsonar.projectName=${appName}",
                                "-Dsonar.sources=.",
                                "-Dsonar.exclusions=**/*_test.go,**/vendor/**",
                                "-Dsonar.go.coverage.reportPaths=coverage.out",
                                "-Dsonar.language=go"
                            ].join(' ')

                            // Panggil Sonar Scanner
                            // Pastikan nama 'SonarQube' sesuai settingan di Manage Jenkins > System
                            withSonarQubeEnv('SonarQube') { 
                                sh "${env.SCANNER_HOME}/bin/sonar-scanner ${scannerParams}"
                            }
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    // Menunggu hasil dari SonarQube (Pass/Fail)
                    // Timeout 5 menit agar tidak hang selamanya
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Building Docker Image: ${appName}:${imageTag}"
                            // Pastikan user jenkins punya akses docker, atau gunakan sudo jika perlu
                            sh "docker build -t ${dockerRegistry}/${appName}:${imageTag} ."
                        }
                    }
                }
            }

            stage('Push to Registry') {
                steps {
                    script {
                        echo "Pushing Image to Registry..."
                        // Login & Push menggunakan kredensial Jenkins
                        withCredentials([usernamePassword(credentialsId: env.DOCKER_CREDENTIAL_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                            sh "echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin"
                            sh "docker push ${dockerRegistry}/${appName}:${imageTag}"
                        }
                    }
                }
            }

            // Uncomment stage ini jika ingin deploy via SSH
            /*
            stage('Deploy to Server') {
                steps {
                    script {
                        echo "Deploying container..."
                        // Tambahkan logic deploy SSH atau Docker Run disini
                        // sh "docker run -d -p ${deployPort}:${deployPort} --name ${appName} ${dockerRegistry}/${appName}:${imageTag}"
                    }
                }
            }
            */
        }
        
        post {
            always {
                echo 'Pipeline finished.'
                cleanWs() // Bersihkan workspace agar hemat disk
            }
            success {
                echo 'Build Success!'
            }
            failure {
                echo 'Build Failed :('
            }
        }
    }
}
