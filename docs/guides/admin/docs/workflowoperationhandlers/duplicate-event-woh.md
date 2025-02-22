Duplicate Event Workflow Operation
===============================

```Id: duplicate-event```

Description
-----------

The duplicate event operation can be used to duplicate an event by copying an existing one. The main use case are events,
which contain a series of different presentations which were all recorded with just one recording. In order to create
separate events for each presentation, the original recording can be copied and each copy can be cut to only contain
one presentation. If the original event was already published, the duplicate won't be published right away. The user will
have to publish it manually when he is done editing it.

For each duplicated event the new media package ID is stored as a workflow property:

|Name                                    |Example                                                             |Description                                    |
|----------------------------------------|--------------------------------------------------------------------|-----------------------------------------------|
|duplicate_media_package_*number*_id     |`duplicate_media_package_1_id=e72f2265-472a-49ae-bc04-8301d94b4b1a` |Media package ID of the duplicated event       | \*
|duplicate_media_package_ids             |`duplicate_media_package_ids=e72f2265-472a-49ae-bc04-8301d94b4b1a, a32e2265-472a-49ae-bc04-8351d94b4b1c` | comma separated list of Media package IDs of the duplicated event |

\* will be deprecated

Parameter Table
---------------

|Configuration Key    |Example                                    |Description                                          |
|---------------------|-------------------------------------------|-----------------------------------------------------|
|source-flavors       |`archive`                                  |Copy any mediapackage elements with one of these (comma separated) flavors.|
|source-tags          |`*/*`                                      |Copy any mediapackage elements with one of these (comma separated) tags.|
|target-tags          |`+copied`                                  |Apply these (comma separated) tags to any copied media package elements. If a target-tag starts with a '-', it will be removed from preexisting tags, if a target-tag starts with a '+', it will be added to preexisting tags. If there is no prefix, all preexisting tags are removed and replaced by the target-tags.|
|property-namespaces  |`org.opencastproject.assetmanager.security`|Copy all asset manager properties of these (comma separated) namespaces.|
|copy-number-prefix   |`copy`                                     |The prefix used for the number of the copy which is appended to the title of the new event.     |
|number-of-events     |`2`                                        |How many events to create.|
|max-number-of-events |`1000`                                     |How many events are allowed to be created at maximum.|
|no-suffix            |`true`                                     |Boolean to decide if the number suffix (`copy #`) is not attached (true ... no attachment, false ...`copy #` is attached) |
|set-series-id        |`3547a900-e0ee-4e3f-9e67-2157cd42c700`     |Id of the series the copied events should be added to |
|set-title            |`copy of mediapackage`                     |Title of the newly created events. Default to original event title. If `no-suffix` is set to `false` a `copy #` will be attached |
|set-start-date-time  |`2021-12-02T22:22:22`                      |Date and time to be set on the newly created events. Default to orignial event date. |


Operation Example
-----------------

    <operation
      id="duplicate-event"
      fail-on-error="true"
      exception-handler-workflow="partial-error"
      description="Duplicate event">
      <configurations>
        <configuration key="source-flavors">*/*</configuration>
        <configuration key="number-of-events">${numberOfEvents}</configuration>
        <configuration key="max-number-of-events">1000</configuration>
        <configuration key="target-tags"></configuration> 
        <configuration key="no-suffix">false</configuration
        <configuration key="set-series-id">${seriesId}</configuration>
        <configuration key="set-title">${mpTitle}</configuration>
        <configuration key="set-start-date-time">${startDateTime}</configuration>
        <configuration key="property-namespaces">
          org.opencastproject.assetmanager.security
        </configuration>
        <configuration key="copy-number-prefix">copy</configuration>
      </configurations>
    </operation>

