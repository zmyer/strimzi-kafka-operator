# LDAP binding delegation

The `io.enmasse.barnabas.authentication.ldap.LdapBindingLoginModule` is a 
JAAS login module that configures the Kafka broker to delegate Kafka SASL 
`PLAIN` authentication to an LDAP bind.

    +--------------+  SASL/PLAIN  +--------------+              +-------------+
    | Kafka client |------------->| Kafka broker |------------->| LDAP Server | 
    +--------------+   Kafka      +--------------+   LDAP       +-------------+

This is useful if it is preferred to maintain the user database in LDAP
than in ZooKeeper.

Currently the bind must be a "simple bind", because the LDAP client 
API used doesn't support SASL binds.

Because the password is sent as plaintext from the Kafka client and the 
Kafka broker this configuration should only be used when either:

* the network is trusted, or
* the Kafka client to broker communication and the broker to LDAP server 
  communcation is encrypted.

The kafka client needs to be configured with and `sasl.mechanism=PLAIN` and
the normal Kafka `PlainLoginModule`:

    sasl.mechanism=PLAIN
    sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
      username="tom" \
      password="tom-password";

The kafka broker needs to be configured with `sasl.enabled.mechanisms=PLAIN`
and a JAAS configuration:

    KafkaServer {
      io.enmasse.barnabas.authentication.ldap.LdapBindingLoginModule required
      ldap_urls="ldap://localhost:10389"
      bind="simple"
      dn_format="uid=%s,ou=kafka,dc=example,dc=com"
      ;
    };
    
The parameters are:

* `ldap_urls` a comma-separated list of `ldap://` or `ldaps://` URLs 
  specifying a host and, optionally, a port. The list will be shuffled and 
  a connection attempted to each server until a connection is successful, 
  or the list is exhausted. 
* `bind` currently only `simple` is supported
* `dn_format` a format string with a single `%s` placeholder, this is used as 
  a template for the DN of the authenticating user.  
  

# AMQP connection delegation

## SASL PLAIN

The `io.enmasse.barnabas.authentication.amqp.DelegatingPlainSaslServerLoginModule` is a 
JAAS login module that configures the Kafka broker to delegate Kafka SASL 
`PLAIN` authentication to an AMQP connect. 

    +--------------+  SASL/PLAIN  +--------------+  SASL/PLAIN  +-------------+
    | Kafka client |------------->| Kafka broker |------------->| AMQP Server | 
    +--------------+   Kafka      +--------------+   AMQP       +-------------+

This is useful if it is preferred to maintain the user database in something 
other than ZooKeeper. For example, users could be stored in 
[Keycloak](http://keycloak.org) and the AMQP server provided by a 
Keycloak plugin.

The Kafka client should be configured like this:

    security.protocol=SASL_PLAINTEXT
    sasl.mechanism=PLAIN
    sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
        username="tom" \
        password="tom";


The Kafka broker should be configured with `sasl.enabled.mechanisms=PLAIN`,
run with 
`EXTRA_ARGS='-Djava.security.auth.login.config=config/jaas.conf' bin/kafka-server-start.sh config/server.properties`
and the given `jaas.conf` should look like this: 

    KafkaServer {
      io.enmasse.barnabas.authentication.amqp.DelegatingPlainSaslServerLoginModule required
      amqp_host="localhost"
      amqp_port="5672"
      realm="master"
      ;
    };

Note: It currently only possible to provide a single host and port, which makes
the AMQP server a single point of failure.

## SASL SCRAM

This component provides a Java `SaslServer` implementation which
forwards SASL message exchanges (over AMQP) to a Keycloak plugin 
(provided by Enmasse).

The main benefit of this approach compared with Kafka's native SASL-SCRAM
support is that Kafka brokers don't need access to a username & credential 
database to authenticate clients, whereas Kafka's native support requires 
the usename and password present in plaintext in the JAAS config file.

This component comprises a number of classes in the 
`io.enmasse.barnabas.authentication.scram` package:

1. The `DelegatingScramSaslServerLoginModule` is a JAAS login module which 
   is used to initialize the `DelegatingScramSaslProvider` when it is 
   configured via JAAS.
2. The `DelegatingScramSaslProvider` is `java.security.Provider`. It's 
    `intialize()` method registers a `DelegatingScramSaslServerFactory` 
    with `java.security.Security`.
3. The `DelegatingScramSaslServerFactory` is used as a factory for
   `DelegatingScramSaslServer`.
4. The `DelegatingScramSaslServer` is the class which actually does the SASL 
   message forwarding.
   
The first two items in the above list are simply copying the pattern Kafka 
itself uses for configuring/initializing its own SASL components. 

### Configuration

Currently the plugin is configured via system properties. This is because the 
Kafka broker does not pass along the information provided in the 
JAAS configuration when initializing a SASL-SCRAM plugin. The system 
properties are:

* `barnabas.sasl.delegation.amqp.host` The hostname of the AMQP server to 
  connect to.
* `barnabas.sasl.delegation.amqp.port` The port of the AMQP server to 
  connect to.
* `barnabas.sasl.delegation.sasl.host` The SASL host that the SASL 
  authenication is nominally to. When using this with the Keycloak plugin, 
  this corresponds to the Keycloak realm. 


### Use   

To use this plugin:

1. You need to configure JAAS to use the login module:

        KafkaServer {
          io.enmasse.barnabas.authentication.scram.DelegatingScramSaslServerLoginModule required
          ;
        };
           
2. When starting a broker you need to tell it about the above JAAS config. 
   You also need to provide system properties to tell the plugin about the 
   AMQP server to forward the SASL messages to. The `EXTRA_ARGS` environment 
   variable can be used for this:
   
        EXTRA_ARGS='-Djava.security.auth.login.config=/home/tom/messaging/kafka/kafka/config/jaas.conf \
                    -Dbarnabas.sasl.delegation.amqp.host=localhost \
                    -Dbarnabas.sasl.delegation.amqp.port=5672 \
                    -Dbarnabas.sasl.delegation.sasl.host=master' \
                    ../bin/kafka-server-start.sh
