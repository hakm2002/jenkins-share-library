def call(Map config = [:]) {
    def appName = config.appName ?: 'my-go-app'
    def projectDir = config.projectDir ?: '.' 
    def deployPort = config.deployPort ?: '8080'
    def sonarCredId = config.sonarCredId ?: 'sonar-token'
    def dockerRegistry = config.dockerRegistry ?: 'docker.io'
    def imageTag = "latest"

    pipeline {
        agent any

        environment {
            GO111MODULE = 'on'
            CGO_ENABLED = '0'
            PATH = "/usr/local/go/bin:${env.PATH}"
            DOCKER_CREDENTIAL_ID = 'dockerhub-id-hakm' 
        }

        stages {
            stage('Checkout Scm') {
                steps {
                    checkout scm
                }
            }

            stage('Unit Test & Coverage') {
                steps {
                    dir(projectDir) {
                        script {
                            echo "Running Unit Tests in ${projectDir}..."
                            sh 'go mod tidy'
                            // Unit test jalan, walau coverage 0% tidak masalah untuk testing pipeline
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
                            
                            // === PERBAIKAN DISINI ===
                            // Mengambil path tool yang bernama 'sonar-scanner' dari Jenkins Tools
                            def scannerHome = tool 'sonar-scanner'
                            
                            def scannerParams = [
                                "-Dsonar.projectKey=${appName}",
                                "-Dsonar.projectName=${appName}",
                                "-Dsonar.sources=.",
                                "-Dsonar.exclusions=**/*_test.go,**/vendor/**",
                                "-Dsonar.go.coverage.reportPaths=coverage.out",
                                "-Dsonar.language=go"
                            ].join(' ')

                            // Menggunakan variabel scannerHome yang sudah didefinisikan
                            withSonarQubeEnv('SonarQube') { 
                                sh "${scannerHome}/bin/sonar-scanner ${scannerParams}"
                            }
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
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
                            sh "docker build -t ${dockerRegistry}/${appName}:${imageTag} ."
                        }
                    }
                }
            }

            stage('Push to Registry') {
                steps {
                    script {
                        echo "Pushing Image to Registry..."
                        withCredentials([usernamePassword(credentialsId: env.DOCKER_CREDENTIAL_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                            sh "echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin"
                            sh "docker push ${dockerRegistry}/${appName}:${imageTag}"
                        }
                    }
                }
            }
        }
        
        post {
            always {
                echo 'Pipeline finished.'
                cleanWs()
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
