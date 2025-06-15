# MapImage
A useful plugin for utility/moderation purposes that allows you to create a map corresponding to any image URL. Server needs internet on each startup for this to work, and needs to be able to access the image URL every time it starts.

## Usage
`/mapimage url` lets you get the map with the image being the image URL of your choice. Include https://, or it will not work.

`/mapimage url size` lets you customize the size of the image you generate. It's useful for bigger boards and images. Size format is HxW, so 2x4 would generate a map matrix 2 high and 4 wide in maps. The maps start from top left and go left to right on each row until bottom right.

## Perms
`mapimage.use` is default to op, and corresponds to the /mapimage command. Using luckperms or another permissions updater you can change this, or just change the default in the source code and build.

## Questions
Add me on Discord, 'tommustbe12', and tell me what you're confused on. Thanks!
