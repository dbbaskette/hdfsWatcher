# Service Registry Implementation Plan for hdfsWatcher

## 🎯 **Overview**

This plan outlines the implementation of Service Registry support for the hdfsWatcher application to enable service discovery in Cloud Foundry environments when deployed via Spring Cloud Data Flow (SCDF).

## 📊 **Current State vs Target State**

### Current State
- **Spring Boot**: 3.4.5
- **Spring Cloud**: 2024.0.1  
- **Deployment**: Standalone Cloud Foundry apps
- **Service Discovery**: None

### Target State  
- **Spring Boot**: 3.5.4
- **Spring Cloud**: 2025.0.0
- **Deployment**: SCDF-managed apps with service registry binding
- **Service Discovery**: Eureka-based service registry via Cloud Foundry services

## 🔧 **Implementation Steps**

### Phase 1: Dependency Updates

#### 1.1 Update Spring Boot and Spring Cloud Versions
```xml
<!-- Update parent version -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.4</version>
    <relativePath/>
</parent>

<!-- Update Spring Cloud version -->
<properties>
    <spring-cloud.version>2025.0.0</spring-cloud.version>
</properties>
```

#### 1.2 Add Service Registry Dependencies
```xml
<!-- Eureka Client for service discovery -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>

<!-- Cloud Foundry environment support -->
<dependency>
    <groupId>io.pivotal.cfenv</groupId>
    <artifactId>java-cfenv-boot</artifactId>
    <version>2.4.0</version>
</dependency>

<!-- Spring Cloud Services for Cloud Foundry -->
<dependency>
    <groupId>io.pivotal.spring.cloud</groupId>
    <artifactId>spring-cloud-services-starter-service-registry</artifactId>
    <version>4.1.3</version>
</dependency>
```

### Phase 2: Configuration Changes

#### 2.1 Create application-cloud.yml
Create a new cloud-specific configuration file with service registry settings:

```yaml
spring:
  application:
    name: hdfswatcher
  cloud:
    service-registry:
      auto-registration:
        enabled: true
        register-management: true
        fail-fast: false

# Enhanced management endpoints for service discovery
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info,env,configprops,discovery,prometheus
  endpoint:
    health:
      show-details: always
      show-components: always

# Enhanced logging for cloud environments
logging:
  level:
    com.baskettecase.hdfsWatcher: INFO
    org.springframework.cloud.netflix.eureka: DEBUG
    com.netflix.discovery: DEBUG
    org.springframework.cloud.service: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n"
```

#### 2.2 Update application-cloud.properties
Enhance the existing cloud configuration to reduce logging noise while maintaining service discovery visibility.

### Phase 3: Application Updates

#### 3.1 Main Application Class
The reference implementation shows that `@SpringBootApplication` is sufficient - no additional annotations needed as auto-configuration handles Eureka client setup.

#### 3.2 Health Check Enhancements
Ensure actuator endpoints provide comprehensive health information for service registry.

### Phase 4: Cloud Foundry Integration

#### 4.1 Service Binding in SCDF
When deploying via SCDF, bind the Service Registry service:

```bash
# Example SCDF stream definition with service binding
dataflow:> stream create --name mystream --definition "hdfswatcher --service-registry=my-service-registry | processor | sink"
```

#### 4.2 Environment Variables
Key environment variables that will be auto-configured via Cloud Foundry service binding:
- `EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE`
- `EUREKA_CLIENT_REGISTER_WITH_EUREKA`
- `EUREKA_CLIENT_FETCH_REGISTRY`

## 🔄 **Migration Strategy**

### Step 1: Version Compatibility Check
- Verify Spring Cloud 2025.0.0 compatibility with existing dependencies
- Check for breaking changes in Spring Boot 3.5.4

### Step 2: Gradual Implementation
1. ✅ Create feature branch (completed)
2. 🔄 Update dependencies
3. 🔄 Add cloud configuration
4. 🔄 Test locally with embedded Eureka (if possible)
5. 🔄 Deploy to CF environment for integration testing

### Step 3: Validation
- Verify service registration in Eureka dashboard
- Test service discovery between components
- Validate health checks and metrics endpoints
- Ensure SCDF stream deployment works correctly

## 🚀 **Benefits**

### For SCDF Deployments
- **Automatic Service Discovery**: Apps can discover each other dynamically
- **Load Balancing**: Built-in client-side load balancing via Ribbon
- **Health Monitoring**: Enhanced health checks for better operational visibility
- **Configuration Management**: Centralized configuration via Spring Cloud Config (if added later)

### For Cloud Foundry Integration
- **Native CF Integration**: Leverages CF service bindings
- **Auto-Configuration**: Minimal manual configuration required
- **Platform Services**: Integrates with platform-provided service registry

## 🛠 **Technical Considerations**

### Compatibility Matrix
- **Spring Boot 3.5.4** + **Spring Cloud 2025.0.0** = ✅ Latest stable
- **Java 21**: ✅ Supported
- **Cloud Foundry**: ✅ Via Spring Cloud Services
- **SCDF 2.11.5**: ✅ Compatible with Spring Cloud 2025.0.0

### Potential Issues & Mitigations
1. **Dependency Conflicts**: 
   - Mitigation: Thorough testing after version upgrades
   - Use `mvn dependency:tree` to check for conflicts

2. **Configuration Changes**:
   - Mitigation: Maintain backward compatibility with existing deployments
   - Use profile-specific configurations

3. **Network Policies**:
   - Mitigation: Ensure CF network policies allow Eureka communication
   - Document required network access patterns

## 📝 **Testing Strategy**

### Local Testing
1. Unit tests for new configuration
2. Integration tests with embedded test registry
3. Actuator endpoint verification

### Cloud Testing  
1. Deploy to CF development environment
2. Verify service registration
3. Test inter-service communication
4. Validate SCDF stream deployment

## 🎉 **Success Criteria**

- ✅ Application successfully registers with Service Registry in CF
- ✅ Health checks report service registry status
- ✅ SCDF can deploy apps with service registry binding
- ✅ Service discovery works between stream components
- ✅ No breaking changes to existing functionality
- ✅ Enhanced operational visibility through discovery endpoints

## 📚 **References**

- [Spring Cloud 2025.0.0 Release Notes](https://spring.io/blog/2024/11/22/spring-cloud-2025-0-0-available-now)
- [Spring Cloud Services for Cloud Foundry](https://docs.vmware.com/en/Spring-Cloud-Services-for-VMware-Tanzu/index.html)
- [SCDF Service Binding Documentation](https://dataflow.spring.io/docs/installation/cloudfoundry/cf-cli/#service-binding)
- Reference Implementation: `imc-telemetry-processor`
