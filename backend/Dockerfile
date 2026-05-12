FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# 配置系统包镜像源（可选，加速apt-get）
RUN sed -i 's@http://.*archive.ubuntu.com@https://mirrors.aliyun.com@g' /etc/apt/sources.list && \
    sed -i 's@http://.*security.ubuntu.com@https://mirrors.aliyun.com@g' /etc/apt/sources.list

# 安装 Maven（构建阶段才需要；不会进入最终镜像）
RUN apt-get update --fix-missing && \
    apt-get install -y --no-install-recommends maven && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# 复制 pom.xml（便于依赖缓存）
COPY pom.xml .

# 配置 Maven 镜像源（阿里云优先）
RUN mkdir -p /root/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?>' > /root/.m2/settings.xml && \
    echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"' >> /root/.m2/settings.xml && \
    echo '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' >> /root/.m2/settings.xml && \
    echo '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">' >> /root/.m2/settings.xml && \
    echo '  <mirrors>' >> /root/.m2/settings.xml && \
    echo '    <mirror>' >> /root/.m2/settings.xml && \
    echo '      <id>aliyunmaven</id>' >> /root/.m2/settings.xml && \
    echo '      <mirrorOf>*</mirrorOf>' >> /root/.m2/settings.xml && \
    echo '      <name>阿里云公共仓库</name>' >> /root/.m2/settings.xml && \
    echo '      <url>https://maven.aliyun.com/repository/public</url>' >> /root/.m2/settings.xml && \
    echo '    </mirror>' >> /root/.m2/settings.xml && \
    echo '    <mirror>' >> /root/.m2/settings.xml && \
    echo '      <id>aliyun-spring</id>' >> /root/.m2/settings.xml && \
    echo '      <mirrorOf>spring-milestones</mirrorOf>' >> /root/.m2/settings.xml && \
    echo '      <name>阿里云Spring仓库</name>' >> /root/.m2/settings.xml && \
    echo '      <url>https://maven.aliyun.com/repository/spring</url>' >> /root/.m2/settings.xml && \
    echo '    </mirror>' >> /root/.m2/settings.xml && \
    echo '  </mirrors>' >> /root/.m2/settings.xml && \
    echo '  <profiles>' >> /root/.m2/settings.xml && \
    echo '    <profile>' >> /root/.m2/settings.xml && \
    echo '      <id>default</id>' >> /root/.m2/settings.xml && \
    echo '      <repositories>' >> /root/.m2/settings.xml && \
    echo '        <repository>' >> /root/.m2/settings.xml && \
    echo '          <id>aliyunmaven</id>' >> /root/.m2/settings.xml && \
    echo '          <name>阿里云公共仓库</name>' >> /root/.m2/settings.xml && \
    echo '          <url>https://maven.aliyun.com/repository/public</url>' >> /root/.m2/settings.xml && \
    echo '          <releases><enabled>true</enabled></releases>' >> /root/.m2/settings.xml && \
    echo '          <snapshots><enabled>true</enabled></snapshots>' >> /root/.m2/settings.xml && \
    echo '        </repository>' >> /root/.m2/settings.xml && \
    echo '      </repositories>' >> /root/.m2/settings.xml && \
    echo '      <pluginRepositories>' >> /root/.m2/settings.xml && \
    echo '        <pluginRepository>' >> /root/.m2/settings.xml && \
    echo '          <id>aliyunmaven</id>' >> /root/.m2/settings.xml && \
    echo '          <name>阿里云公共仓库</name>' >> /root/.m2/settings.xml && \
    echo '          <url>https://maven.aliyun.com/repository/public</url>' >> /root/.m2/settings.xml && \
    echo '          <releases><enabled>true</enabled></releases>' >> /root/.m2/settings.xml && \
    echo '          <snapshots><enabled>true</enabled></snapshots>' >> /root/.m2/settings.xml && \
    echo '        </pluginRepository>' >> /root/.m2/settings.xml && \
    echo '      </pluginRepositories>' >> /root/.m2/settings.xml && \
    echo '    </profile>' >> /root/.m2/settings.xml && \
    echo '  </profiles>' >> /root/.m2/settings.xml && \
    echo '  <activeProfiles><activeProfile>default</activeProfile></activeProfiles>' >> /root/.m2/settings.xml && \
    echo '</settings>' >> /root/.m2/settings.xml

# 复制源码并构建（源码只存在于 build 阶段，不会进入最终镜像）
COPY src ./src
RUN mvn clean package -DskipTests -s /root/.m2/settings.xml


FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app

# 最终镜像仅包含运行产物（jar），不包含 src/pom/maven 缓存
COPY --from=build /app/target/luanshu-agent-backend-1.0.0.jar /app/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]